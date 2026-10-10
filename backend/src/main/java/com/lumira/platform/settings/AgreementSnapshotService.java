package com.lumira.platform.settings;

import com.lumira.platform.identity.api.AgreementSnapshotPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Agreement publication lock and immutable text snapshot used by public registration. */
@Service
public class AgreementSnapshotService implements AgreementSnapshotPort {
    private static final long PUBLICATION_LOCK = 481029L;
    private final JdbcClient jdbc;
    private final SettingDocuments documents;

    public AgreementSnapshotService(JdbcClient jdbc, SettingDocuments documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public Document current() {
        var setting = documents.read("agreements", Agreements.class, Agreements::defaults);
        return new Document(
                setting.version(),
                setting.data().userAgreementMarkdown(),
                setting.data().privacyAgreementMarkdown());
    }

    @Override
    @Transactional
    public Snapshot capture(long expectedVersion) {
        lockShared();
        Document current = current();
        if (current.version() != expectedVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "registrationAgreementChanged");
        if (!current.complete())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "registrationUnavailable");

        String hash =
                contentHash(current.userAgreementMarkdown(), current.privacyAgreementMarkdown());
        return jdbc.sql(
                        "INSERT INTO platform_agreement_snapshot(version,user_agreement_markdown,privacy_agreement_markdown,content_hash) VALUES(:version,:user,:privacy,:hash) ON CONFLICT(version) DO UPDATE SET content_hash=platform_agreement_snapshot.content_hash WHERE platform_agreement_snapshot.content_hash=EXCLUDED.content_hash RETURNING id,version,user_agreement_markdown,privacy_agreement_markdown,content_hash")
                .param("version", current.version())
                .param("user", current.userAgreementMarkdown())
                .param("privacy", current.privacyAgreementMarkdown())
                .param("hash", hash)
                .query(
                        (rs, row) ->
                                new Snapshot(
                                        rs.getObject("id", java.util.UUID.class),
                                        rs.getLong("version"),
                                        rs.getString("user_agreement_markdown"),
                                        rs.getString("privacy_agreement_markdown"),
                                        rs.getString("content_hash")))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT, "registrationAgreementChanged"));
    }

    @Transactional
    public void lockForPublication() {
        jdbc.sql("SELECT pg_advisory_xact_lock(" + PUBLICATION_LOCK + ")")
                .query(
                        resultSet -> {
                            resultSet.next();
                            return 0;
                        });
    }

    private void lockShared() {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(" + PUBLICATION_LOCK + ")")
                .query(
                        resultSet -> {
                            resultSet.next();
                            return 0;
                        });
    }

    private static String contentHash(String user, String privacy) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] userBytes = user.getBytes(StandardCharsets.UTF_8);
            byte[] privacyBytes = privacy.getBytes(StandardCharsets.UTF_8);
            digest.update(
                    java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(userBytes.length).array());
            digest.update(userBytes);
            digest.update(
                    java.nio.ByteBuffer.allocate(Integer.BYTES)
                            .putInt(privacyBytes.length)
                            .array());
            digest.update(privacyBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Agreement hash unavailable", impossible);
        }
    }
}
