package com.lumira.platform.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.MailConfigurationPort;
import com.lumira.platform.identity.CaptchaProofAccess;
import com.lumira.platform.identity.PasswordRecoveryService;
import com.lumira.platform.settings.SmtpSettings;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

/** Disposable loopback fixture. Trust override exists only in this test, never production. */
class SmtpTlsTest {
    @TempDir static Path directory;
    private static SSLContext tls;

    @BeforeAll
    static void certificate() throws Exception {
        Path store = directory.resolve("localhost.p12");
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "fixture",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "2",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=dns:localhost",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "local-fixture-only",
                                "-keypass",
                                "local-fixture-only",
                                "-noprompt")
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("keytool.log").toFile())
                        .start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Fixture certificate generation timed out");
        }
        assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, "local-fixture-only".toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "local-fixture-only".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }

    private static class Fixture implements AutoCloseable {
        final ServerSocket listener;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<Void> task;
        boolean secured, authenticated;
        byte[] body;
        String failure;

        Fixture(boolean implicit, boolean rejectAuth, boolean loseAck) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(10000);
            task =
                    executor.submit(
                            () -> {
                                try (var accepted = listener.accept()) {
                                    Socket socket = accepted;
                                    socket.setSoTimeout(10000);
                                    if (implicit) socket = secure(socket);
                                    var reader = reader(socket);
                                    var writer = writer(socket);
                                    reply(writer, "220 localhost ESMTP");
                                    for (String command; (command = reader.readLine()) != null; ) {
                                        if (command.startsWith("EHLO "))
                                            reply(
                                                    writer,
                                                    secured
                                                            ? "250-localhost\r\n250 AUTH PLAIN"
                                                            : "250-localhost\r\n250 STARTTLS");
                                        else if (command.equals("STARTTLS")) {
                                            reply(writer, "220 Begin TLS");
                                            socket = secure(socket);
                                            reader = reader(socket);
                                            writer = writer(socket);
                                        } else if (command.startsWith("AUTH PLAIN")) {
                                            assertTrue(secured, "Authentication must follow TLS");
                                            String encoded =
                                                    command.length() > 11
                                                            ? command.substring(11)
                                                            : null;
                                            if (encoded == null) {
                                                reply(writer, "334 ");
                                                encoded = reader.readLine();
                                            }
                                            byte[] supplied = Base64.getDecoder().decode(encoded);
                                            String[] fields =
                                                    new String(supplied, StandardCharsets.UTF_8)
                                                            .split("\0", -1);
                                            boolean matches =
                                                    fields.length == 3
                                                            && (fields[0].isEmpty()
                                                                    || fields[0].equals("sender"))
                                                            && fields[1].equals("sender")
                                                            && fields[2].equals("fixture-password");
                                            Arrays.fill(supplied, (byte) 0);
                                            assertTrue(
                                                    matches,
                                                    "Fixture authentication payload mismatch");
                                            authenticated = !rejectAuth;
                                            reply(
                                                    writer,
                                                    rejectAuth
                                                            ? "535 Authentication refused"
                                                            : "235 Authenticated");
                                        } else if (command.startsWith("MAIL FROM:")
                                                || command.startsWith("RCPT TO:")) {
                                            assertTrue(secured && authenticated);
                                            reply(writer, "250 OK");
                                        } else if (command.equals("DATA")) {
                                            assertTrue(secured && authenticated);
                                            reply(writer, "354 Continue");
                                            var data = new ByteArrayOutputStream();
                                            for (String line;
                                                    (line = reader.readLine()) != null
                                                            && !line.equals("."); ) {
                                                if (line.startsWith("..")) line = line.substring(1);
                                                data.write(
                                                        (line + "\r\n")
                                                                .getBytes(StandardCharsets.UTF_8));
                                                assertTrue(data.size() < 200000);
                                            }
                                            body = data.toByteArray();
                                            if (loseAck) return null;
                                            reply(writer, "250 Accepted");
                                        } else if (command.equals("QUIT")) {
                                            reply(writer, "221 Bye");
                                            return null;
                                        } else if (command.equals("RSET"))
                                            reply(writer, "250 Reset");
                                        else fail("Unexpected SMTP fixture command");
                                    }
                                } catch (IOException e) {
                                    failure = e.getClass().getSimpleName();
                                }
                                return null;
                            });
        }

        private Socket secure(Socket raw) throws IOException {
            var socket =
                    (SSLSocket)
                            tls.getSocketFactory()
                                    .createSocket(raw, "localhost", raw.getPort(), true);
            socket.setUseClientMode(false);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            secured = true;
            return socket;
        }

        private static BufferedReader reader(Socket s) throws IOException {
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        }

        private static PrintWriter writer(Socket s) throws IOException {
            return new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        private static void reply(PrintWriter writer, String value) {
            writer.print(value + "\r\n");
            writer.flush();
        }

        void await() throws Exception {
            task.get(15, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            listener.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(12, TimeUnit.SECONDS));
        }
    }

    private SmtpGateway gateway(boolean trustFixture) {
        return trustFixture
                ? new SmtpGateway(
                        session -> {
                            session.getProperties()
                                    .put("mail.smtp.ssl.socketFactory", tls.getSocketFactory());
                            return session.getTransport("smtp");
                        })
                : new SmtpGateway();
    }

    private static CaptchaProofAccess recoveryCaptcha() {
        var access = mock(CaptchaProofAccess.class);
        var port = mock(CaptchaProofPort.class);
        when(access.begin(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            CaptchaProofPort.Action action = invocation.getArgument(0);
                            return new CaptchaProofAccess.Gate(
                                    port,
                                    action,
                                    new CaptchaProofPort.Requirement(false, "smtp-fixture", null));
                        });
        return access;
    }

    private SmtpGateway.Result send(Fixture fixture, boolean implicit, boolean trust, String host) {
        var data =
                new MailConfigurationPort.Settings(
                        true,
                        host,
                        fixture.listener.getLocalPort(),
                        implicit ? "TLS" : "STARTTLS",
                        true,
                        "sender",
                        "sender@example.test",
                        "测试发件人");
        byte[] secret = "fixture-password".getBytes(StandardCharsets.UTF_8);
        var result =
                gateway(trust)
                        .send(
                                new SmtpConnection(data, secret),
                                new OutgoingMail(
                                        "recipient@example.test", "加密邮件", "正文\n.leading dot"));
        assertArrayEquals(new byte[secret.length], secret);
        return result;
    }

    @Test
    void realProviderDeliversUtf8OverImplicitTlsAndStartTls() throws Exception {
        for (boolean implicit : new boolean[] {true, false})
            try (var fixture = new Fixture(implicit, false, false)) {
                assertEquals(
                        SmtpGateway.Result.ACCEPTED, send(fixture, implicit, true, "localhost"));
                fixture.await();
                assertNull(fixture.failure);
                assertTrue(fixture.authenticated);
                var mime =
                        new MimeMessage(
                                Session.getInstance(new Properties()),
                                new ByteArrayInputStream(fixture.body));
                assertEquals("加密邮件", mime.getSubject());
                assertTrue(mime.getContent().toString().contains(".leading dot"));
                assertTrue(mime.getContent().toString().contains("正文"));
                assertEquals(1, mime.getAllRecipients().length);
            }
    }

    @Test
    void authenticationFailureNeverSubmitsData() throws Exception {
        try (var fixture = new Fixture(true, true, false)) {
            assertEquals(
                    SmtpGateway.Result.AUTHENTICATION_FAILED,
                    send(fixture, true, true, "localhost"));
            fixture.await();
            assertFalse(fixture.authenticated);
            assertNull(fixture.body);
        }
    }

    @Test
    void defaultTrustRejectsUntrustedCertificate() throws Exception {
        try (var fixture = new Fixture(true, false, false)) {
            assertEquals(
                    SmtpGateway.Result.CONNECTION_FAILED, send(fixture, true, false, "localhost"));
            fixture.await();
            assertFalse(fixture.authenticated);
            assertNull(fixture.body);
        }
    }

    @Test
    void trustedCertificateStillRequiresMatchingHostname() throws Exception {
        try (var fixture = new Fixture(true, false, false)) {
            assertEquals(
                    SmtpGateway.Result.CONNECTION_FAILED, send(fixture, true, true, "127.0.0.1"));
            fixture.await();
            assertFalse(fixture.authenticated);
            assertNull(fixture.body);
        }
    }

    @Test
    void disconnectAfterDataIsNotReportedAsSafeToRetry() throws Exception {
        try (var fixture = new Fixture(true, false, true)) {
            assertEquals(
                    SmtpGateway.Result.DELIVERY_UNCERTAIN, send(fixture, true, true, "localhost"));
            fixture.await();
            assertNotNull(fixture.body);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "LUMIRA_TEST_DB_URL",
            matches = ".+")
    void persistentQueueToRealTlsAndAuditRecovery() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        var admin =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                url, user, password));
        String schema = "qa_mail_tls_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var ds =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                            user,
                            password);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            var client = org.springframework.jdbc.core.simple.JdbcClient.create(ds);
            jdbc.execute(TestSchema.table("platform_mail_delivery"));
            jdbc.execute(TestSchema.table("platform_audit"));
            var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
            var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
            var keys =
                    new com.lumira.platform.common.SecretKeys(
                            "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                            "primary");
            var store =
                    new MailDeliveryStore(client, keys, new tools.jackson.databind.ObjectMapper());
            var audit = new com.lumira.platform.audit.AuditRepository(client);
            for (boolean failAudit : new boolean[] {false, true})
                try (var fixture = new Fixture(true, false, false)) {
                    UUID id = UUID.randomUUID();
                    assertEquals(
                            Boolean.TRUE,
                            tx.execute(
                                    s ->
                                            store.enqueue(
                                                    id,
                                                    new OutgoingMail(
                                                            "recipient@example.test",
                                                            "Queued TLS",
                                                            "Encrypted queue content"),
                                                    3600)));
                    var source = org.mockito.Mockito.mock(SmtpConnectionSource.class);
                    org.mockito.Mockito.when(source.load())
                            .thenAnswer(
                                    i ->
                                            new SmtpConnection(
                                                    new MailConfigurationPort.Settings(
                                                            true,
                                                            "localhost",
                                                            fixture.listener.getLocalPort(),
                                                            "TLS",
                                                            true,
                                                            "sender",
                                                            "sender@example.test",
                                                            ""),
                                                    "fixture-password"
                                                            .getBytes(StandardCharsets.UTF_8)));
                    if (failAudit)
                        jdbc.execute(
                                "CREATE FUNCTION reject_mail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='mail.ACCEPTED' THEN RAISE EXCEPTION 'Fixture rejection'; END IF; RETURN NEW; END $$; CREATE TRIGGER reject_mail_audit BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION reject_mail_audit()");
                    try (var worker =
                            new WorkerScope(
                                    new MailDeliveryWorker(
                                            store, source, gateway(true), audit, manager))) {
                        var claim = tx.execute(s -> store.claim().orElseThrow());
                        worker.value.process(claim, 10000);
                        fixture.await();
                        assertNotNull(fixture.body);
                        assertEquals(
                                "Queued TLS",
                                new MimeMessage(
                                                Session.getInstance(new Properties()),
                                                new ByteArrayInputStream(fixture.body))
                                        .getSubject());
                        assertEquals(
                                failAudit ? "SENDING" : "ACCEPTED",
                                store.status(id).orElseThrow().state());
                        assertEquals(
                                failAudit ? 1 : 2,
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM platform_audit WHERE target_id=?",
                                        Integer.class,
                                        id));
                        if (failAudit) {
                            jdbc.update(
                                    "UPDATE platform_mail_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                                    id);
                            worker.value.poll();
                            worker.value.poll();
                            assertEquals("UNCERTAIN", store.status(id).orElseThrow().state());
                            assertEquals(
                                    1,
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM platform_audit WHERE target_id=? AND action='mail.UNCERTAIN'",
                                            Integer.class,
                                            id));
                        }
                        assertTrue(store.claim().isEmpty());
                    }
                }
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "LUMIRA_TEST_DB_URL",
            matches = ".+")
    void recoveryRequestUsesStoredCredentialsAndDeliveredCodeResetsPassword() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        var admin =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                url, user, password));
        String schema = "qa_recovery_tls_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var ds =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                            user,
                            password);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            var client = org.springframework.jdbc.core.simple.JdbcClient.create(ds);
            jdbc.execute(
                    "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT,password_hash TEXT,enabled BOOLEAN,deleted_at TIMESTAMPTZ)");
            jdbc.execute(
                    "CREATE TABLE platform_password_change_requirement(user_id UUID PRIMARY KEY)");
            jdbc.execute(
                    "CREATE TABLE platform_bootstrap_password_change_requirement(user_id UUID PRIMARY KEY,primary_credential_revision UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMPTZ,CHECK(completed_at IS NULL OR completed_at>=created_at))");
            jdbc.execute(
                    "CREATE TABLE platform_authority(user_id UUID,authority TEXT,PRIMARY KEY(user_id,authority))");
            jdbc.execute("CREATE TABLE spring_session(principal_name TEXT)");
            jdbc.execute(TestSchema.table("platform_setting"));
            jdbc.execute(TestSchema.table("platform_setting_revision"));
            jdbc.execute(TestSchema.table("platform_secret"));
            jdbc.execute(TestSchema.table("platform_mail_delivery"));
            jdbc.execute(TestSchema.table("platform_account_email"));
            jdbc.execute(TestSchema.table("platform_password_reset"));
            jdbc.execute(TestSchema.table("platform_audit"));
            var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            UUID owner = UUID.randomUUID(), setting = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO platform_user(id,username,password_hash,enabled) VALUES(?,'recovery-fixture',?,true)",
                    owner,
                    encoder.encode("old-password-fixture"));
            jdbc.update(
                    "INSERT INTO platform_account_email(user_id,email,verified_at) VALUES(?,'recipient@example.test',now())",
                    owner);
            jdbc.update("INSERT INTO spring_session VALUES('recovery-fixture'),('other')");
            var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
            var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
            var mapper = new tools.jackson.databind.ObjectMapper();
            var keys =
                    new com.lumira.platform.common.SecretKeys(
                            "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                            "primary");
            var secrets = new com.lumira.platform.settings.SecretStore(client, keys);
            var store = new MailDeliveryStore(client, keys, mapper);
            var audit = new com.lumira.platform.audit.AuditRepository(client);
            var stored =
                    new com.lumira.platform.settings.SmtpSettingsService(
                            new com.lumira.platform.settings.SettingStore(client, null, null),
                            secrets,
                            keys,
                            null,
                            mapper,
                            null);
            // Settings validation (correctly) refuses "localhost", so the stored host is 127.0.0.1;
            // the
            // fixture certificate is issued for "localhost", so only the connect target is swapped
            // back.
            var source =
                    new SmtpConnectionSource(
                            new com.lumira.platform.common.MailConfigurationPort() {
                                @Override
                                public Settings readSettings() {
                                    return stored.readSettings();
                                }

                                @Override
                                public Configuration load() {
                                    var real = stored.load();
                                    var s = real.settings();
                                    return new Configuration(
                                            new Settings(
                                                    s.enabled(),
                                                    "localhost",
                                                    s.port(),
                                                    s.security(),
                                                    s.authentication(),
                                                    s.username(),
                                                    s.fromAddress(),
                                                    s.fromName()),
                                            real.password());
                                }
                            });
            var recovery =
                    new PasswordRecoveryService(
                            client,
                            new com.lumira.platform.identity.PasswordResetStore(client),
                            new MailSenderAdapter(store, source),
                            audit,
                            encoder,
                            null,
                            recoveryCaptcha(),
                            manager);
            try (var fixture = new Fixture(true, false, false)) {
                var settings =
                        new SmtpSettings(
                                true,
                                "127.0.0.1",
                                fixture.listener.getLocalPort(),
                                "TLS",
                                true,
                                "sender",
                                "sender@example.test",
                                "");
                jdbc.update(
                        "INSERT INTO platform_setting(id,section,version,payload,updated_by) VALUES(?,'smtp',1,CAST(? AS jsonb),'fixture')",
                        setting,
                        mapper.writeValueAsString(settings));
                byte[] credential = "fixture-password".getBytes(StandardCharsets.UTF_8);
                try {
                    tx.executeWithoutResult(
                            status ->
                                    secrets.save(
                                            "settings:" + setting + ":smtp-password",
                                            0,
                                            credential));
                } finally {
                    Arrays.fill(credential, (byte) 0);
                }
                tx.executeWithoutResult(
                        status ->
                                recovery.request(
                                        "recovery-fixture",
                                        "zh-CN",
                                        null,
                                        new MockHttpServletRequest()));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform_mail_delivery WHERE state='PENDING'",
                                Integer.class));
                try (var worker =
                        new WorkerScope(
                                new MailDeliveryWorker(
                                        store, source, gateway(true), audit, manager))) {
                    var claim = tx.execute(status -> store.claim().orElseThrow());
                    worker.value.process(claim, 10000);
                    fixture.await();
                    assertTrue(fixture.authenticated);
                    assertNull(fixture.failure);
                    assertNotNull(fixture.body);
                    var received =
                            new MimeMessage(
                                    Session.getInstance(new Properties()),
                                    new ByteArrayInputStream(fixture.body));
                    assertEquals("重置账号密码", received.getSubject());
                    assertEquals(
                            "recipient@example.test", received.getAllRecipients()[0].toString());
                    String body = received.getContent().toString().strip();
                    String token = body.substring(body.lastIndexOf('\n') + 1).strip();
                    assertEquals(43, token.length());
                    assertEquals("ACCEPTED", store.status(claim.id()).orElseThrow().state());
                    assertFalse(
                            jdbc.queryForObject(
                                            "SELECT payload FROM platform_mail_delivery",
                                            String.class)
                                    .contains(token));
                    assertEquals(
                            Boolean.TRUE,
                            tx.execute(
                                    status ->
                                            recovery.confirm(
                                                    "recovery-fixture",
                                                    token,
                                                    "new-password-fixture",
                                                    null,
                                                    new MockHttpServletRequest())));
                    assertEquals(
                            Boolean.FALSE,
                            tx.execute(
                                    status ->
                                            recovery.confirm(
                                                    "recovery-fixture",
                                                    token,
                                                    "new-password-fixture",
                                                    null,
                                                    new MockHttpServletRequest())));
                    assertTrue(
                            encoder.matches(
                                    "new-password-fixture",
                                    jdbc.queryForObject(
                                            "SELECT password_hash FROM platform_user",
                                            String.class)));
                    assertEquals(
                            List.of("other"),
                            jdbc.queryForList(
                                    "SELECT principal_name FROM spring_session", String.class));
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_audit WHERE action='account.passwordRecovered'",
                                    Integer.class));
                }
            }
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private record WorkerScope(MailDeliveryWorker value) implements AutoCloseable {
        public void close() {
            value.close();
        }
    }
}
