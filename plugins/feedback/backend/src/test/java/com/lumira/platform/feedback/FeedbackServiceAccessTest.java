package com.lumira.platform.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.FileContentPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.identity.AdministrationGuard;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Exercises the service boundary directly, including principals without global feedback scope. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FeedbackServiceAccessTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID scopedManager = UUID.randomUUID();
    private final UUID statusManager = UUID.randomUUID();
    private final UUID replyManager = UUID.randomUUID();
    private final UUID administrator = UUID.randomUUID();
    private final UUID operationOnly = UUID.randomUUID();
    private final UUID createOnly = UUID.randomUUID();
    private final UUID pageOnly = UUID.randomUUID();
    private final UUID noPageReader = UUID.randomUUID();
    private final UUID noReadWriter = UUID.randomUUID();
    private final UUID noActionReader = UUID.randomUUID();
    private final UUID subtreeManager = UUID.randomUUID();
    private final UUID previewManager = UUID.randomUUID();
    private final UUID charlie = UUID.randomUUID();
    private final UUID deletedOwner = UUID.randomUUID();
    private final UUID rootOrganization = UUID.randomUUID();
    private final UUID childOrganization = UUID.randomUUID();
    private final UUID otherOrganization = UUID.randomUUID();
    private final UUID previewRole = UUID.randomUUID();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FeedbackStore store;
    private FeedbackService service;
    private FileContentPort files;
    private String schema;
    private final FeedbackPolicy policy =
            new FeedbackPolicy(
                    Map.of("OPEN", false, "PROCESSING", false, "RESOLVED", true),
                    Set.of("LOW", "NORMAL", "HIGH"),
                    Set.of("QUESTION", "BUG", "FEATURE", "ACCOUNT", "OTHER"),
                    "OPEN",
                    "NORMAL");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_feedback_access_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);

        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var client = JdbcClient.create(dataSource);

        for (String table :
                List.of(
                        "platform_user",
                        "platform_authority",
                        "platform_user_role",
                        "platform_role",
                        "platform_role_authority",
                        "platform_organization",
                        "platform_user_organization",
                        "platform_audit",
                        "platform_file",
                        "platform_feedback",
                        "platform_feedback_file")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        jdbc.execute("CREATE TABLE platform_effective_authority(user_id UUID, authority TEXT)");
        jdbc.execute(
                "ALTER TABLE platform_feedback ADD CONSTRAINT qa_feedback_owner_fk "
                        + "FOREIGN KEY (owner_id) REFERENCES platform_user(id) ON DELETE SET NULL");
        jdbc.execute(
                "ALTER TABLE platform_feedback ADD CONSTRAINT qa_feedback_handler_fk "
                        + "FOREIGN KEY (handled_by) REFERENCES platform_user(id) ON DELETE SET NULL");
        jdbc.execute(
                "ALTER TABLE platform_file ADD CONSTRAINT qa_file_owner_fk "
                        + "FOREIGN KEY (owner_id) REFERENCES platform_user(id) ON DELETE SET NULL");
        jdbc.execute(
                "ALTER TABLE platform_feedback_file ADD CONSTRAINT qa_feedback_file_feedback_fk "
                        + "FOREIGN KEY (feedback_id) REFERENCES platform_feedback(id) ON DELETE CASCADE");
        jdbc.execute(
                "ALTER TABLE platform_feedback_file ADD CONSTRAINT qa_feedback_file_file_fk "
                        + "FOREIGN KEY (file_id) REFERENCES platform_file(id) ON DELETE RESTRICT");
        jdbc.execute(
                "CREATE FUNCTION qa_track_platform_file_owner() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN IF NEW.owner_id IS NOT NULL THEN NEW.orphaned_at=NULL;"
                        + " ELSIF TG_OP='INSERT' THEN NEW.orphaned_at=clock_timestamp();"
                        + " ELSIF OLD.owner_id IS NOT NULL OR NEW.orphaned_at IS NULL THEN"
                        + " NEW.orphaned_at=clock_timestamp(); END IF; RETURN NEW; END; $$");
        jdbc.execute(
                "CREATE TRIGGER qa_platform_file_track_owner BEFORE INSERT OR UPDATE OF owner_id"
                        + " ON platform_file FOR EACH ROW EXECUTE FUNCTION qa_track_platform_file_owner()");
        jdbc.execute(
                "CREATE TABLE platform_test_permission_scope(actor_name TEXT, permission TEXT,"
                        + " data_scope TEXT, PRIMARY KEY(actor_name, permission))");
        jdbc.execute(
                "CREATE TABLE platform_test_role_preview_scope(actor_name TEXT, role_id UUID,"
                        + " role_version BIGINT, permission TEXT, data_scope TEXT,"
                        + " PRIMARY KEY(actor_name, role_id, role_version, permission))");
        jdbc.execute(
                "CREATE FUNCTION platform_test_scope_visible_users(actor_name TEXT, permission"
                        + " TEXT, requested_scope TEXT) RETURNS TABLE(user_id UUID) LANGUAGE SQL"
                        + " STABLE AS $$ WITH RECURSIVE actor AS (SELECT id FROM platform_user"
                        + " WHERE username=actor_name AND enabled AND deleted_at IS NULL), own_orgs AS"
                        + " (SELECT membership.organization_id FROM platform_user_organization"
                        + " membership JOIN actor ON actor.id=membership.user_id), descendants AS"
                        + " (SELECT organization_id AS id FROM own_orgs UNION SELECT organization.id FROM"
                        + " platform_organization organization JOIN descendants parent ON"
                        + " organization.parent_id=parent.id WHERE organization.enabled) SELECT target.id"
                        + " FROM platform_user target WHERE target.deleted_at IS NULL AND"
                        + " (requested_scope='ALL' OR (requested_scope='SELF' AND target.id IN"
                        + " (SELECT id FROM actor)) OR (requested_scope='ORGANIZATION' AND EXISTS"
                        + " (SELECT 1 FROM platform_user_organization membership WHERE"
                        + " membership.user_id=target.id AND membership.organization_id IN"
                        + " (SELECT organization_id FROM own_orgs))) OR (requested_scope='SUBTREE'"
                        + " AND EXISTS (SELECT 1 FROM platform_user_organization membership WHERE"
                        + " membership.user_id=target.id AND membership.organization_id IN"
                        + " (SELECT id FROM descendants)))) $$");
        jdbc.execute(
                "CREATE FUNCTION platform_visible_users(actor_name TEXT, permission_name TEXT)"
                        + " RETURNS TABLE(user_id UUID) LANGUAGE SQL STABLE AS $$ SELECT visible.user_id"
                        + " FROM platform_test_permission_scope scope CROSS JOIN LATERAL"
                        + " platform_test_scope_visible_users($1,$2,scope.data_scope) visible"
                        + " WHERE scope.actor_name=$1 AND scope.permission=$2 $$");
        jdbc.execute(
                "CREATE FUNCTION platform_role_preview_visible_users(actor_name TEXT,"
                        + " permission_name TEXT, target_role_id UUID, target_role_version BIGINT)"
                        + " RETURNS TABLE(user_id UUID) LANGUAGE SQL STABLE AS $$ SELECT real.user_id"
                        + " FROM platform_visible_users($1,$2) real WHERE $3 IS NULL UNION SELECT"
                        + " real.user_id FROM platform_visible_users($1,$2) real JOIN"
                        + " platform_test_role_preview_scope preview ON preview.actor_name=$1 AND"
                        + " preview.role_id=$3 AND preview.role_version=$4 AND"
                        + " preview.permission=$2 JOIN LATERAL"
                        + " platform_test_scope_visible_users($1,$2,preview.data_scope) target"
                        + " ON target.user_id=real.user_id $$");
        jdbc.execute(
                "CREATE VIEW platform_global_authority AS "
                        + "SELECT grants.user_id, grants.authority FROM platform_authority grants "
                        + "UNION "
                        + "SELECT assigned.user_id, grants.authority "
                        + "FROM platform_user_role assigned "
                        + "JOIN platform_role role ON role.id=assigned.role_id "
                        + "AND role.enabled AND role.data_scope='ALL' "
                        + "JOIN platform_role_authority grants ON grants.role_id=role.id");
        addUser(alice, "alice");
        addUser(bob, "bob");
        addUser(scopedManager, "scoped-manager");
        addUser(statusManager, "status-manager");
        addUser(replyManager, "reply-manager");
        addUser(administrator, "administrator");
        addUser(operationOnly, "operation-only");
        addUser(createOnly, "create-only");
        addUser(pageOnly, "page-only");
        addUser(noPageReader, "no-page-reader");
        addUser(noReadWriter, "no-read-writer");
        addUser(noActionReader, "no-action-reader");
        addUser(subtreeManager, "subtree-manager");
        addUser(previewManager, "preview-manager");
        addUser(charlie, "charlie");
        addUser(deletedOwner, "deleted-owner");
        addAuthority(alice, FeedbackService.ACCESS, FeedbackService.READ);
        addAuthority(bob, FeedbackService.ACCESS, FeedbackService.READ);
        addAuthority(scopedManager, FeedbackService.ACCESS, FeedbackService.READ);
        addAuthority(
                statusManager,
                FeedbackService.ACCESS,
                FeedbackService.READ,
                FeedbackService.UPDATE);
        addAuthority(
                replyManager, FeedbackService.ACCESS, FeedbackService.READ, FeedbackService.REPLY);
        addAuthority(
                administrator,
                FeedbackService.ACCESS,
                FeedbackService.READ,
                FeedbackService.UPDATE,
                FeedbackService.REPLY);
        addAuthority(operationOnly, FeedbackService.CREATE);
        addAuthority(createOnly, FeedbackService.ACCESS, FeedbackService.CREATE);
        addAuthority(pageOnly, FeedbackService.ACCESS);
        addAuthority(noPageReader, FeedbackService.READ);
        addAuthority(
                noReadWriter,
                FeedbackService.ACCESS,
                FeedbackService.UPDATE,
                FeedbackService.REPLY);
        addAuthority(noActionReader, FeedbackService.ACCESS, FeedbackService.READ);
        addAuthority(subtreeManager, FeedbackService.ACCESS, FeedbackService.READ);
        addAuthority(previewManager, FeedbackService.ACCESS, FeedbackService.READ);
        addGlobalAuthority(
                administrator,
                FeedbackService.ACCESS,
                FeedbackService.READ,
                FeedbackService.UPDATE,
                FeedbackService.REPLY);

        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name,enabled)"
                        + " VALUES (?,NULL,'root','Root',true), (?,?,'child','Child',true),"
                        + " (?,NULL,'other','Other',true)",
                rootOrganization,
                childOrganization,
                rootOrganization,
                otherOrganization);
        addOrganization(scopedManager, rootOrganization);
        addOrganization(bob, rootOrganization);
        addOrganization(charlie, childOrganization);
        addOrganization(statusManager, otherOrganization);
        addOrganization(replyManager, rootOrganization);
        addOrganization(subtreeManager, rootOrganization);

        setScopes("scoped-manager", FeedbackService.ACCESS, "ORGANIZATION");
        setScopes("scoped-manager", FeedbackService.READ, "SUBTREE");
        setScopes("status-manager", FeedbackService.ACCESS, "ALL");
        setScopes("status-manager", FeedbackService.READ, "ALL");
        setScopes("status-manager", FeedbackService.UPDATE, "ORGANIZATION");
        setScopes("reply-manager", FeedbackService.ACCESS, "ALL");
        setScopes("reply-manager", FeedbackService.READ, "ALL");
        setScopes("reply-manager", FeedbackService.REPLY, "ORGANIZATION");
        setScopes("administrator", FeedbackService.ACCESS, "ALL");
        setScopes("administrator", FeedbackService.READ, "ALL");
        setScopes("administrator", FeedbackService.UPDATE, "ALL");
        setScopes("administrator", FeedbackService.REPLY, "ALL");
        setScopes("no-read-writer", FeedbackService.ACCESS, "ALL");
        setScopes("no-read-writer", FeedbackService.REPLY, "ALL");
        setScopes("no-page-reader", FeedbackService.READ, "ALL");
        setScopes("no-action-reader", FeedbackService.ACCESS, "ALL");
        setScopes("no-action-reader", FeedbackService.READ, "ALL");
        setScopes("subtree-manager", FeedbackService.ACCESS, "SUBTREE");
        setScopes("subtree-manager", FeedbackService.READ, "ALL");
        setScopes("preview-manager", FeedbackService.ACCESS, "ALL");
        setScopes("preview-manager", FeedbackService.READ, "ALL");

        store = new FeedbackStore(client, new AuditRepository(client));
        var catalog = mock(FeedbackCatalog.class);
        when(catalog.load())
                .thenReturn(new FeedbackCatalog.Catalog(policy, List.of(), List.of(), List.of()));
        files = mock(FileContentPort.class);
        when(files.findReadyImageForContent(anyString(), any()))
                .thenThrow(
                        new ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND, "fileNotFound"));
        when(files.findManagedFeedbackImageForContent(any()))
                .thenAnswer(invocation -> new FileContentPort.Image(invocation.getArgument(0), 32));
        AdministrationPort guard = new AdministrationGuard(client);
        service = new FeedbackService(store, catalog, guard, client, files);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private void addUser(UUID id, String username) {
        jdbc.update(
                "INSERT INTO platform_user(id, username, display_name, password_hash)"
                        + " VALUES (?, ?, ?, 'unused')",
                id,
                username,
                username);
    }

    private void addGlobalAuthority(UUID userId, String... authorities) {
        for (String authority : authorities) {
            jdbc.update(
                    "INSERT INTO platform_authority(user_id, authority) VALUES (?, ?)",
                    userId,
                    authority);
        }
    }

    private void setScopes(String actor, String permission, String scope) {
        jdbc.update(
                "INSERT INTO platform_test_permission_scope(actor_name,permission,data_scope)"
                        + " VALUES (?,?,?) ON CONFLICT(actor_name,permission) DO UPDATE SET"
                        + " data_scope=EXCLUDED.data_scope",
                actor,
                permission,
                scope);
    }

    private void addOrganization(UUID userId, UUID organizationId) {
        jdbc.update(
                "INSERT INTO platform_user_organization(user_id,organization_id) VALUES (?,?)",
                userId,
                organizationId);
    }

    private void addAuthority(UUID userId, String... authorities) {
        for (String authority : authorities) {
            jdbc.update(
                    "INSERT INTO platform_effective_authority(user_id, authority) VALUES (?, ?)",
                    userId,
                    authority);
        }
    }

    private FeedbackStore.Detail create(UUID owner, String username, String title) {
        return tx.execute(
                status ->
                        store.create(
                                owner,
                                username,
                                title,
                                "<p>Details</p>",
                                "NORMAL",
                                "OTHER",
                                policy));
    }

    private static void assertDenied(String code, Runnable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getReason());
    }

    private UUID addImageTo(FeedbackStore.Detail feedback) {
        UUID image = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_file"
                        + "(id,owner_id,storage_backend,original_name,state,size_bytes,sha256,media_type)"
                        + " VALUES (?,?,'local','image.png','READY',32,repeat('c',64),'image/png')",
                image,
                feedback.ownerId());
        tx.executeWithoutResult(
                status -> store.attachImages(feedback.id(), feedback.ownerId(), List.of(image)));
        return image;
    }

    private <T> T asPreview(String actor, UUID roleId, long version, Supplier<T> action) {
        var permissions = Set.of(FeedbackService.ACCESS, FeedbackService.READ);
        var authorization =
                new RequestAuthorizationContext(
                        actor,
                        null,
                        null,
                        permissions,
                        Set.of(),
                        Map.of(),
                        permissions,
                        Set.of(),
                        Map.of(),
                        "test-revision",
                        "test-revision",
                        true,
                        new RequestAuthorizationContext.Preview(
                                "ACTIVE",
                                roleId,
                                "Preview role",
                                version,
                                OffsetDateTime.now(),
                                OffsetDateTime.now().plusMinutes(5),
                                false,
                                null));
        var snapshot = new AuthenticationRequestContext.Snapshot(actor, null, null, authorization);
        try (var ignored = AuthenticationRequestContext.bind(snapshot)) {
            return action.get();
        }
    }

    @Test
    void ownerViewsStayOwnAndManagementListIntersectsPageAndReadScopes() {
        var aliceRow = create(alice, "alice", "Alice feedback");
        var bobRow = create(bob, "bob", "Bob feedback");
        var charlieRow = create(charlie, "charlie", "Charlie feedback");

        var mine = service.mine("alice", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(1, mine.total());
        assertEquals(aliceRow.id(), mine.items().get(0).id());
        assertDenied("feedbackNotFound", () -> service.mine("alice", bobRow.id()));

        var managed = service.managed("scoped-manager", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(1, managed.total());
        assertEquals(bobRow.id(), managed.items().get(0).id());
        assertDenied("feedbackNotFound", () -> service.managed("scoped-manager", aliceRow.id()));
        assertDenied("feedbackNotFound", () -> service.managed("scoped-manager", charlieRow.id()));
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "scoped-manager",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, "RESOLVED", null)));
        assertEquals("OPEN", store.findOwned(bob, bobRow.id()).status());
    }

    @Test
    void fourScopeValuesFilterManagementRowsBeforePagination() {
        var selfRow = create(subtreeManager, "subtree-manager", "Self feedback");
        var bobRow = create(bob, "bob", "Bob feedback");
        var charlieRow = create(charlie, "charlie", "Charlie feedback");
        create(alice, "alice", "Other organization feedback");

        setScopes("subtree-manager", FeedbackService.ACCESS, "SELF");
        setScopes("subtree-manager", FeedbackService.READ, "ALL");
        var selfOnly = service.managed("subtree-manager", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(1, selfOnly.total());
        assertEquals(selfRow.id(), selfOnly.items().get(0).id());

        setScopes("subtree-manager", FeedbackService.ACCESS, "SUBTREE");
        var subtree = service.managed("subtree-manager", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(3, subtree.total());
        assertTrue(subtree.items().stream().anyMatch(row -> row.id().equals(bobRow.id())));
        assertTrue(subtree.items().stream().anyMatch(row -> row.id().equals(charlieRow.id())));

        var organization =
                service.managed("scoped-manager", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(2, organization.total());
        assertEquals(
                Set.of(selfRow.id(), bobRow.id()),
                organization.items().stream()
                        .map(FeedbackStore.Summary::id)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(organization.items().stream().anyMatch(row -> row.id().equals(bobRow.id())));
        assertTrue(organization.items().stream().anyMatch(row -> row.id().equals(selfRow.id())));
        assertFalse(
                organization.items().stream().anyMatch(row -> row.id().equals(charlieRow.id())));

        var all = service.managed("administrator", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(4, all.total());
    }

    @Test
    void pageAndReadAreRequiredEvenWhenWriteActionsAreGranted() {
        assertDenied(
                "forbidden",
                () ->
                        service.create(
                                "operation-only",
                                new FeedbackService.Create(
                                        "No access", "<p>Denied</p>", "NORMAL", "OTHER")));
        assertDenied(
                "forbidden",
                () -> service.managed("operation-only", new PageRequest(1, 20, ""), "", "", ""));

        var bobRow = create(bob, "bob", "Read required");
        assertDenied(
                "forbidden",
                () -> service.managed("no-page-reader", new PageRequest(1, 20, ""), "", "", ""));
        assertDenied("forbidden", () -> service.mine("no-page-reader", bobRow.id()));
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "no-read-writer",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, "RESOLVED", null)));
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "no-read-writer",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, null, "reply denied")));
        assertDenied(
                "forbidden",
                () -> service.managed("no-read-writer", new PageRequest(1, 20, ""), "", "", ""));
        assertEquals("OPEN", store.findOwned(bob, bobRow.id()).status());
    }

    @Test
    void optionsRequireAccessAndEitherReadOrCreate() {
        assertEquals(policy, service.options("no-action-reader").policy());
        assertEquals(policy, service.options("create-only").policy());

        var created =
                service.create(
                        "create-only",
                        new FeedbackService.Create(
                                "Create only", "<p>Can submit</p>", "NORMAL", "OTHER"));
        assertEquals(createOnly, created.ownerId());
        assertDenied("forbidden", () -> service.mine("create-only", created.id()));
        assertDenied("forbidden", () -> service.managementOptions("create-only"));
        assertDenied(
                "forbidden",
                () -> service.managed("create-only", new PageRequest(1, 20, ""), "", "", ""));

        assertDenied("forbidden", () -> service.options("page-only"));
        assertDenied("forbidden", () -> service.options("operation-only"));
        assertDenied("forbidden", () -> service.options("no-read-writer"));
    }

    @Test
    void detailCapabilitiesReflectIndependentActionGrantAndTargetScope() {
        var bobRow = create(bob, "bob", "Bob feedback");
        var statusRow = create(statusManager, "status-manager", "Status manager feedback");

        var statusDetail = service.managed("status-manager", statusRow.id());
        assertTrue(statusDetail.canUpdateStatus());
        assertFalse(statusDetail.canReply());
        var statusUpdated =
                tx.execute(
                        status ->
                                service.handle(
                                        "status-manager",
                                        statusRow.id(),
                                        new FeedbackService.Handle(1L, "PROCESSING", null)));
        assertTrue(statusUpdated.canUpdateStatus());
        assertFalse(statusUpdated.canReply());
        assertEquals("PROCESSING", statusUpdated.status());
        assertDenied(
                "forbidden",
                () ->
                        tx.execute(
                                status ->
                                        service.handle(
                                                "status-manager",
                                                statusRow.id(),
                                                new FeedbackService.Handle(
                                                        2L, "RESOLVED", "reply denied"))));
        assertEquals(null, store.findForManagement(statusRow.id()).adminReply());

        var replyDetail = service.managed("reply-manager", bobRow.id());
        assertFalse(replyDetail.canUpdateStatus());
        assertTrue(replyDetail.canReply());
        var replyUpdated =
                tx.execute(
                        status ->
                                service.handle(
                                        "reply-manager",
                                        bobRow.id(),
                                        new FeedbackService.Handle(1L, null, "reply allowed")));
        assertFalse(replyUpdated.canUpdateStatus());
        assertTrue(replyUpdated.canReply());
        assertEquals("reply allowed", replyUpdated.adminReply());
        assertDenied(
                "forbidden",
                () ->
                        tx.execute(
                                status ->
                                        service.handle(
                                                "reply-manager",
                                                bobRow.id(),
                                                new FeedbackService.Handle(
                                                        2L, "RESOLVED", "second reply"))));
        assertEquals("OPEN", store.findForManagement(bobRow.id()).status());
        assertEquals("reply allowed", store.findForManagement(bobRow.id()).adminReply());

        var deniedScope = service.managed("status-manager", bobRow.id());
        assertFalse(deniedScope.canUpdateStatus());
        assertFalse(deniedScope.canReply());
        assertDenied(
                "feedbackNotFound",
                () ->
                        tx.execute(
                                status ->
                                        service.handle(
                                                "status-manager",
                                                bobRow.id(),
                                                new FeedbackService.Handle(1L, "RESOLVED", null))));
    }

    @Test
    void readWithoutActionCanInspectButCannotChangeEitherField() {
        var bobRow = create(bob, "bob", "No action grant");
        var detail = service.managed("no-action-reader", bobRow.id());
        assertFalse(detail.canUpdateStatus());
        assertFalse(detail.canReply());
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "no-action-reader",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, "RESOLVED", null)));
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "no-action-reader",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, null, "reply denied")));
        assertEquals("OPEN", store.findForManagement(bobRow.id()).status());
        assertEquals(null, store.findForManagement(bobRow.id()).adminReply());
    }

    @Test
    void managementImagesRequireVisibleBoundFeedbackButOwnerReadsKeepUnboundUploads() {
        var hiddenTicket = create(charlie, "charlie", "Hidden attachment");
        UUID hiddenImage = addImageTo(hiddenTicket);
        assertDenied(
                "feedbackNotFound", () -> service.managementImage("scoped-manager", hiddenImage));
        verify(files, never()).findManagedFeedbackImageForContent(hiddenImage);

        var visibleTicket = create(bob, "bob", "Visible attachment");
        UUID visibleImage = addImageTo(visibleTicket);
        assertTrue(service.managementImage("scoped-manager", visibleImage).sizeBytes() > 0);
        verify(files).findManagedFeedbackImageForContent(visibleImage);

        UUID unboundOwnerImage = UUID.randomUUID();
        assertDenied("fileNotFound", () -> service.image("alice", unboundOwnerImage));
        verify(files).findReadyImageForContent("alice", unboundOwnerImage);
    }

    @Test
    void deletedOwnersRequireGlobalPageAndReadScopes() {
        var row = create(deletedOwner, "deleted-owner", "Deleted owner");
        jdbc.update(
                "UPDATE platform_user SET deleted_at=clock_timestamp(), enabled=false WHERE id=?",
                deletedOwner);

        assertEquals(
                1,
                service.managed("administrator", new PageRequest(1, 20, ""), "", "", "").total());
        assertEquals(row.id(), service.managed("administrator", row.id()).id());
        assertFalse(
                service
                        .managed("scoped-manager", new PageRequest(1, 20, ""), "", "", "")
                        .items()
                        .stream()
                        .anyMatch(item -> item.id().equals(row.id())));
        assertDenied("feedbackNotFound", () -> service.managed("scoped-manager", row.id()));
    }

    @Test
    void hardDeletedOwnerTicketsStayConsistentAcrossGlobalListDetailAndImages() {
        var row = create(deletedOwner, "deleted-owner", "Hard deleted owner");
        UUID image = addImageTo(row);
        jdbc.update("DELETE FROM platform_user WHERE id=?", deletedOwner);

        assertEquals(
                null,
                jdbc.queryForObject(
                        "SELECT owner_id FROM platform_feedback WHERE id=?", UUID.class, row.id()));
        var managed = service.managed("administrator", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(1, managed.total());
        assertEquals(row.id(), managed.items().get(0).id());
        assertEquals(null, service.managed("administrator", row.id()).ownerId());
        assertTrue(service.managementImage("administrator", image).sizeBytes() > 0);

        var scoped = service.managed("scoped-manager", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(0, scoped.total());
        assertDenied("feedbackNotFound", () -> service.managed("scoped-manager", row.id()));
        assertDenied("feedbackNotFound", () -> service.managementImage("scoped-manager", image));
        verify(files, times(1)).findManagedFeedbackImageForContent(image);
    }

    @Test
    void rolePreviewUsesTypedPreviewVisibleUsersForPagedScope() {
        var bobRow = create(bob, "bob", "Preview visible");
        var charlieRow = create(charlie, "charlie", "Preview hidden");
        addOrganization(previewManager, rootOrganization);
        jdbc.update(
                "INSERT INTO platform_test_role_preview_scope(actor_name,role_id,role_version,permission,data_scope)"
                        + " VALUES ('preview-manager',?,?,?,'ORGANIZATION'),"
                        + " ('preview-manager',?,?,?,'ALL')",
                previewRole,
                7L,
                FeedbackService.ACCESS,
                previewRole,
                7L,
                FeedbackService.READ);

        var result =
                asPreview(
                        "preview-manager",
                        previewRole,
                        7L,
                        () ->
                                service.managed(
                                        "preview-manager", new PageRequest(1, 20, ""), "", "", ""));
        assertEquals(1, result.total());
        assertEquals(bobRow.id(), result.items().get(0).id());
        assertFalse(result.items().stream().anyMatch(item -> item.id().equals(charlieRow.id())));
        assertEquals(
                bobRow.id(),
                asPreview(
                                "preview-manager",
                                previewRole,
                                7L,
                                () -> service.managed("preview-manager", bobRow.id()))
                        .id());
    }
}
