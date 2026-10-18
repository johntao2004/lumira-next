package com.lumira.platform.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import com.lumira.platform.identity.api.UserProfileValuesPort;
import com.lumira.platform.organizations.OrganizationService;
import com.lumira.platform.roles.RoleRepository;
import com.lumira.platform.roles.RoleService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Verifies empty-profile projections against the current baseline account table. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class TemplateUserDataAdapterDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void createCurrentSchema() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_template_user_data_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(TestSchema.table("platform_user"));
        TestSchema.indexes("platform_user").forEach(jdbc::execute);
    }

    @AfterEach
    void dropDisposableSchema() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void readValuesNormalizesNullProfileEntriesBeforeReturningImmutableMap() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES (?,?,?,'not-a-real-secret')",
                id,
                "fixture",
                "Fixture");
        var guard = new ReadOnlyUsersGuard();
        var users = mock(UserService.class);
        when(users.find(id, "admin"))
                .thenReturn(new UserSummary(id, "fixture", "Fixture", true, 0, null, null));
        var profileValues = mock(UserProfileValuesPort.class);
        var profile = new LinkedHashMap<String, String>();
        profile.put("customField", null);
        when(profileValues.readForEditor(id))
                .thenReturn(
                        new UserProfileValuesPort.EditorSnapshot(
                                0, 0, List.of(), profile, java.util.Map.of()));
        var adapter =
                new TemplateUserDataAdapter(
                        guard,
                        users,
                        mock(ProfileSchemaPort.class),
                        profileValues,
                        mock(RoleService.class),
                        mock(RoleRepository.class),
                        mock(OrganizationService.class),
                        JdbcClient.create(jdbc.getDataSource()),
                        new ObjectMapper());

        var values = adapter.readValues(id, "admin");

        assertEquals("", values.get("customField"));
        assertEquals("", values.get("realName"));
        assertEquals("", values.get("birthDate"));
        assertEquals("", values.get("region"));
    }

    private static final class ReadOnlyUsersGuard implements AdministrationPort {
        @Override
        public Set<String> require(String actor, String permission) {
            if (PlatformOperation.USER_READ_ROLES.permission().equals(permission)
                    || PlatformPageId.ORGANIZATIONS.accessPermission().equals(permission))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            return Set.of(permission);
        }

        @Override
        public void requireUser(String actor, UUID target, String permission) {}

        @Override
        public void requireGlobal(String actor, String permission) {}

        @Override
        public boolean hasGlobal(String actor, String permission) {
            return true;
        }
    }
}
