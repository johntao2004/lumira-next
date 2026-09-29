package com.lumira.platform.files;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves verified files from the previous local store before the application becomes ready. */
@Component
public final class LegacyFileBlobMigrator implements ApplicationRunner {
    private static final long MIGRATION_LOCK = 481_027L;

    private final FileRepository files;
    private final LocalBlobStore local;
    private final PostgresBlobStore postgres;
    private final DataSource dataSource;

    public LegacyFileBlobMigrator(FileRepository files, LocalBlobStore local,
            PostgresBlobStore postgres, DataSource dataSource) {
        this.files = files;
        this.local = local;
        this.postgres = postgres;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        migrateWithLock(true);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        migrateWithLock(false);
    }

    private void migrateWithLock(boolean wait) {
        try (Connection connection = dataSource.getConnection()) {
            boolean locked = acquireLock(connection, wait);
            if (!locked) return;
            try {
                migratePendingLocalCopies();
                while (true) {
                    var batch = files.readyLocalFiles(50);
                    if (batch.isEmpty()) return;
                    for (var entry : batch) migrate(entry);
                }
            } finally {
                releaseLock(connection);
            }
        } catch (SQLException | IOException | RuntimeException failure) {
            throw new IllegalStateException("Legacy file migration did not complete", failure);
        }
    }

    private boolean acquireLock(Connection connection, boolean wait) throws SQLException {
        String sql = wait ? "SELECT pg_advisory_lock(?)" : "SELECT pg_try_advisory_lock(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, MIGRATION_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                return wait || (result.next() && result.getBoolean(1));
            }
        }
    }

    private void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, MIGRATION_LOCK);
            statement.execute();
        }
    }

    private void migratePendingLocalCopies() throws IOException {
        while (true) {
            var pending = files.pendingLegacyLocalCleanup(100);
            if (pending.isEmpty()) return;
            for (var entry : pending) {
                local.delete(entry.id());
                files.finishLegacyLocalCleanup(entry.id());
            }
        }
    }

    private void migrate(FileRepository.Entry entry) throws IOException {
        try (InputStream source = local.open(entry.id())) {
            postgres.migrateLocal(entry, source);
        }
        // The DB switch is committed before deleting the old copy. A failed unlink is retried on boot.
        local.delete(entry.id());
        files.finishLegacyLocalCleanup(entry.id());
    }
}
