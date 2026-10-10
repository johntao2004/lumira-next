package com.lumira.platform.files;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DeadlineInputStream;
import com.lumira.platform.common.GeneratedAvatarPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AvatarService {
    public record AvatarView(UUID fileId, long version, boolean generated) {}

    private final AvatarRepository avatars;
    private final AccountOwnerPort accounts;
    private final FileRepository files;
    private final FileService storage;
    private final AuditPort audit;
    private final ProfileSchemaPort schemas;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final ObjectProvider<PluginExtensionsPort> plugins;

    public AvatarService(
            AvatarRepository avatars,
            AccountOwnerPort accounts,
            FileRepository files,
            FileService storage,
            AuditPort audit,
            ProfileSchemaPort schemas,
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            ObjectProvider<PluginExtensionsPort> plugins) {
        this.avatars = avatars;
        this.accounts = accounts;
        this.files = files;
        this.storage = storage;
        this.audit = audit;
        this.schemas = schemas;
        this.jdbc = jdbc;
        this.plugins = plugins;
    }

    private void requireEditable(boolean remove) {
        var rule = schemas.read().data().avatar();
        if (rule.isPresent() && !rule.get().visible())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        if (remove && rule.isPresent() && rule.get().required())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatarRequired");
    }

    private void lockSchema() {
        jdbc.sql("SELECT pg_advisory_xact_lock(481026)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
    }

    public AvatarView find(String actor) {
        var owner = accounts.id(actor, false);
        return view(owner, avatars.find(owner, false));
    }

    private AvatarView view(UUID owner, AvatarRepository.Avatar avatar) {
        return new AvatarView(
                avatar.fileId(), avatar.version(), avatar.fileId() == null && generatorAvailable());
    }

    private boolean generatorAvailable() {
        var runtime = plugins.getIfAvailable();
        return runtime != null
                && !runtime.extensions(GeneratedAvatarPort.class, ignored -> Boolean.TRUE)
                        .isEmpty();
    }

    private static void checkVersion(long current, long expected) {
        if (expected < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidVersion");
        if (current != expected)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
    }

    public AvatarView upload(String actor, long version, InputStream source) throws IOException {
        checkVersion(find(actor).version(), version);
        requireEditable(false);
        var image =
                ImageNormalizer.normalize(new DeadlineInputStream(source, Duration.ofMinutes(15)));
        storage.uploadImage(
                actor,
                image,
                () -> {
                    lockSchema();
                    requireEditable(false);
                },
                entry -> {
                    var current = avatars.find(entry.ownerId(), true);
                    checkVersion(current.version(), version);
                    avatars.update(entry.ownerId(), entry.id());
                    retire(actor, entry.ownerId(), current.fileId());
                    audit.append(actor, "account.avatarUpdated", entry.ownerId());
                });
        // This upload committed version+1; another tab may already have changed the current avatar.
        return find(actor);
    }

    @Transactional
    public AvatarView remove(String actor, long version) {
        lockSchema();
        requireEditable(true);
        var owner = accounts.id(actor, true);
        var current = avatars.find(owner, true);
        checkVersion(current.version(), version);
        if (current.fileId() == null) return view(owner, current);
        avatars.update(owner, null);
        retire(actor, owner, current.fileId());
        audit.append(actor, "account.avatarRemoved", owner);
        return view(owner, new AvatarRepository.Avatar(null, current.version() + 1));
    }

    private void retire(String actor, UUID owner, UUID id) {
        if (id == null) return;
        var old =
                files.findOwned(id, owner)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, "fileStateConflict"));
        if (!files.requestDelete(id, owner, old.version()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
        audit.append(actor, "file.deleteRequested", id);
        // Existing scheduled cleanup removes bytes and releases quota after commit.
    }

    public FileRepository.Entry image(String actor, long version) {
        var current = find(actor);
        if (current.fileId() == null || current.version() != version)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
        return storage.find(actor, current.fileId());
    }

    public byte[] generatedImage(String actor, long version) {
        var owner = accounts.id(actor, false);
        var current = avatars.find(owner, false);
        if (current.fileId() != null || current.version() != version)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");

        var runtime = plugins.getIfAvailable();
        if (runtime == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
        List<byte[]> images =
                runtime.extensions(GeneratedAvatarPort.class, port -> port.generate(owner));
        if (images.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
        byte[] png = images.stream().filter(image -> image != null).findFirst().orElse(null);
        if (!isPng(png))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "avatarGeneratorUnavailable");
        return png;
    }

    private static boolean isPng(byte[] image) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (image == null
                || image.length < signature.length
                || image.length > ImageNormalizer.MAXIMUM_BYTES) return false;
        for (int i = 0; i < signature.length; i++) if (image[i] != signature[i]) return false;
        return true;
    }
}
