package com.lumira.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies B52 initialization and manual baseline behavior in a dedicated QA database. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_DATABASE_BASELINE_TEST_DB_URL", matches = ".+")
class DatabaseBaselineMigrationTest {
    private static final String BASELINE_SCRIPT = "B52__platform_initialization.sql";
    private static final String BASELINE_LOCATION = "classpath:db/migration";
    private static final List<String> EMPTY_BUSINESS_TABLES =
            List.of(
                    "platform_user",
                    "platform_user_role",
                    "platform_authority",
                    "platform_organization",
                    "platform_user_organization",
                    "platform_secret",
                    "platform_ai_provider",
                    "platform_feedback",
                    "platform_feedback_file",
                    "platform_file",
                    "platform_file_blob_chunk",
                    "platform_event_outbox",
                    "platform_notification",
                    "platform_setting",
                    "platform_sensitive_word",
                    "spring_session");
    private static final Map<String, String> STABLE_SEED_QUERIES =
            Map.ofEntries(
                    Map.entry(
                            "platform_module_state",
                            "SELECT jsonb_build_object('module_id',module_id,'enabled',enabled,"
                                    + "'version',version)::text FROM public.platform_module_state "
                                    + "ORDER BY module_id"),
                    Map.entry(
                            "platform_module_revision",
                            "SELECT jsonb_build_object('module_id',s.module_id,'version',r.version,"
                                + "'enabled',r.enabled,'changed_by',r.changed_by,'change_reason',r.change_reason)::text"
                                + " FROM public.platform_module_revision r JOIN"
                                + " public.platform_module_state s ON s.id=r.module_state_id ORDER"
                                + " BY s.module_id,r.version"),
                    Map.entry(
                            "platform_dictionary",
                            "SELECT"
                                + " jsonb_build_object('code',code,'name',name,'enabled',enabled,'structure',structure,'remark',remark,'version',version)::text"
                                + " FROM public.platform_dictionary ORDER BY code"),
                    Map.entry(
                            "platform_dictionary_item",
                            "SELECT"
                                + " jsonb_build_object('dictionary_code',d.code,'parent_value',p.value,"
                                + "'value',i.value,'label',i.label,'sort_order',i.sort_order,'enabled',i.enabled,'remark',i.remark,'version',i.version)::text"
                                + " FROM public.platform_dictionary_item i JOIN"
                                + " public.platform_dictionary d ON d.id=i.dictionary_id LEFT JOIN"
                                + " public.platform_dictionary_item p ON p.id=i.parent_id ORDER BY"
                                + " d.code,p.value,i.value"),
                    Map.entry(
                            "platform_navigation_menu",
                            "SELECT"
                                + " jsonb_build_object('menu_code',m.menu_code,'parent_code',p.menu_code,"
                                + "'label_key',m.label_key,'label_default',m.label_default,"
                                + "'menu_type',m.menu_type,'section_key',m.section_key,'path',m.path,"
                                + "'icon',m.icon,'permission_key',m.permission_key,"
                                + "'global_only',m.global_only,'module_id',m.module_id,"
                                + "'sort_order',m.sort_order,'enabled',m.enabled,"
                                + "'built_in',m.built_in,'deleted',m.deleted,'version',m.version,'component_key',m.component_key)::text"
                                + " FROM public.platform_navigation_menu m LEFT JOIN"
                                + " public.platform_navigation_menu p ON p.id=m.parent_id ORDER BY"
                                + " m.menu_code"),
                    Map.entry(
                            "platform_localization_locale",
                            "SELECT jsonb_build_object('locale_code',locale_code,"
                                + "'display_name',display_name,'source_locale',source_locale,"
                                + "'fallback_locale',fallback_locale,'built_in',built_in,"
                                + "'enabled',enabled,'version',version,'created_by',created_by,'updated_by',updated_by)::text"
                                + " FROM public.platform_localization_locale ORDER BY locale_code"),
                    Map.entry(
                            "platform_authentication_policy_lock",
                            "SELECT jsonb_build_object('id',id)::text FROM"
                                    + " public.platform_authentication_policy_lock ORDER BY id"),
                    Map.entry(
                            "platform_global_authentication_policy",
                            "SELECT jsonb_build_object('id',id,'version',version,"
                                + "'enabled_methods',enabled_methods,'mfa_required',mfa_required,'updated_by',updated_by,'change_reason',change_reason)::text"
                                + " FROM public.platform_global_authentication_policy ORDER BY id"),
                    Map.entry(
                            "platform_registration_policy",
                            "SELECT jsonb_build_object('id',id,'version',version,'enabled',enabled,"
                                + "'channels',channels,'default_role_id',default_role_id,'updated_by',updated_by,'change_reason',change_reason)::text"
                                + " FROM public.platform_registration_policy ORDER BY id"),
                    Map.entry(
                            "platform_role",
                            "SELECT jsonb_build_object('id',id,'code',code,'name',name,"
                                    + "'enabled',enabled,'version',version,'data_scope',data_scope,"
                                    + "'file_quota_bytes_limit',file_quota_bytes_limit,"
                                    + "'file_quota_files_limit',file_quota_files_limit)::text "
                                    + "FROM public.platform_role ORDER BY code,id"),
                    Map.entry(
                            "platform_role_authority",
                            "SELECT"
                                + " jsonb_build_object('role_id',role_id,'authority',authority)::text"
                                + " FROM public.platform_role_authority ORDER BY"
                                + " role_id,authority"),
                    Map.entry(
                            "platform_captcha_settings",
                            "SELECT jsonb_build_object('id',id,'version',version,'enabled',enabled,"
                                + "'challenge_type',challenge_type,'access_key_id',access_key_id,"
                                + "'primary_app_id',primary_app_id,'accessible_app_id',accessible_app_id,"
                                + "'timeout_seconds',timeout_seconds,'actions',actions,'updated_by',updated_by,'change_reason',change_reason)::text"
                                + " FROM public.platform_captcha_settings ORDER BY id"),
                    Map.entry(
                            "platform_sensitive_word_revision",
                            "SELECT"
                                + " jsonb_build_object('singleton',singleton,'revision',revision)::text"
                                + " FROM public.platform_sensitive_word_revision ORDER BY"
                                + " singleton"),
                    Map.entry(
                            "platform_permission_legacy_mapping",
                            "SELECT jsonb_build_object('old_authority',old_authority,"
                                    + "'new_authority',new_authority)::text "
                                    + "FROM public.platform_permission_legacy_mapping "
                                    + "ORDER BY old_authority,new_authority"),
                    Map.entry(
                            "platform_permission_projection_component",
                            "SELECT jsonb_build_object('logical_authority',logical_authority,"
                                    + "'source_authority',source_authority,"
                                    + "'source_requires_global',source_requires_global,"
                                    + "'target_scope_source',target_scope_source)::text "
                                    + "FROM public.platform_permission_projection_component "
                                    + "ORDER BY logical_authority,source_authority"),
                    Map.entry(
                            "platform_permission_retired_authority",
                            "SELECT jsonb_build_object('authority',authority)::text "
                                    + "FROM public.platform_permission_retired_authority "
                                    + "ORDER BY authority"));

    @Test
    void freshAndManuallyImportedBaselineAreStableAndIdempotent() throws IOException {
        String url = requiredEnvironment("LUMIRA_DATABASE_BASELINE_TEST_DB_URL");
        String user = requiredEnvironment("LUMIRA_DATABASE_BASELINE_TEST_DB_USER");
        String password = System.getenv("LUMIRA_DATABASE_BASELINE_TEST_DB_PASSWORD");
        String runToken = requiredEnvironment("LUMIRA_QA_RUN_TOKEN");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertDedicatedQaDatabase(jdbc, runToken);
        try {
            resetPublicSchema(jdbc);
            Flyway fresh = configureFlyway(dataSource, false).load();
            fresh.migrate();
            fresh.validate();
            assertEquals("52", fresh.info().current().getVersion().getVersion());

            List<String> freshHistory = historyRows(jdbc);
            assertEquals(1, freshHistory.size(), "A fresh database must apply only B52.");
            assertTrue(freshHistory.get(0).startsWith("52|"), freshHistory.toString());
            assertTrue(freshHistory.get(0).contains(BASELINE_SCRIPT), freshHistory.toString());
            assertCurrentPermissionSchema(jdbc);
            assertDefaultRoleSeeds(jdbc);
            assertNoBusinessRowsOrSecrets(jdbc);

            Map<String, List<String>> freshSchema = schemaSnapshot(jdbc);
            Map<String, List<String>> freshSeeds = stableSeedSnapshot(jdbc);
            fresh.migrate();
            fresh.validate();
            assertEquals(
                    freshHistory, historyRows(jdbc), "Repeated migrate must not change history.");
            assertSnapshotEquals(
                    freshSchema, schemaSnapshot(jdbc), "Fresh schema changed on repeat.");
            assertSnapshotEquals(
                    freshSeeds, stableSeedSnapshot(jdbc), "Stable seeds changed on repeat.");

            resetPublicSchema(jdbc);
            executeBaselineSql(dataSource, baselineSql());
            Map<String, List<String>> importedSchema = schemaSnapshot(jdbc);
            Map<String, List<String>> importedSeeds = stableSeedSnapshot(jdbc);
            assertSnapshotEquals(freshSchema, importedSchema, "Manual import changed the schema.");
            assertSnapshotEquals(freshSeeds, importedSeeds, "Manual import changed stable seeds.");
            assertCurrentPermissionSchema(jdbc);
            assertDefaultRoleSeeds(jdbc);
            assertNoBusinessRowsOrSecrets(jdbc);

            Flyway manualImport = configureFlyway(dataSource, true).load();
            manualImport.migrate();
            manualImport.validate();
            assertEquals("52", manualImport.info().current().getVersion().getVersion());
            List<String> baselineHistory = historyRows(jdbc);
            assertEquals(1, baselineHistory.size(), baselineHistory.toString());
            assertTrue(
                    baselineHistory.get(0).contains("<< Flyway Baseline >>"),
                    baselineHistory.toString());

            manualImport.migrate();
            manualImport.validate();
            assertEquals(baselineHistory, historyRows(jdbc));
            assertSnapshotEquals(
                    importedSchema, schemaSnapshot(jdbc), "Baseline changed imported schema.");
            assertSnapshotEquals(
                    importedSeeds, stableSeedSnapshot(jdbc), "Baseline changed imported seeds.");
            assertNoBusinessRowsOrSecrets(jdbc);
        } finally {
            resetPublicSchema(jdbc);
            assertDedicatedQaDatabase(jdbc, runToken);
        }
    }

    private static FluentConfiguration configureFlyway(
            DriverManagerDataSource dataSource, boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(BASELINE_LOCATION)
                .schemas("public")
                .defaultSchema("public")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("52");
    }

    private static void assertDedicatedQaDatabase(JdbcTemplate jdbc, String runToken) {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertNotNull(database);
        assertTrue(
                database.matches("lumira_qa_database_baseline_[a-z0-9_]+"),
                "Refusing a database outside the dedicated QA namespace: " + database);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM qa_guard.lumira_qa_fixture_guard "
                                + "WHERE id=true AND run_token=?::uuid "
                                + "AND purpose='isolated-security-qa'",
                        Integer.class,
                        runToken),
                "The isolated fixture marker must match this test run.");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class c "
                                + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                                + "WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S')",
                        Integer.class),
                "The public schema must be empty before migration or cleanup.");
    }

    private static void resetPublicSchema(JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    private static void assertCurrentPermissionSchema(JdbcTemplate jdbc) {
        assertEquals(
                Set.of("platform_effective_authority", "platform_global_authority"),
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT c.relname FROM pg_class c "
                                        + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                                        + "WHERE n.nspname='public' AND c.relkind='v' "
                                        + "AND c.relname IN ('platform_effective_authority',"
                                        + "'platform_global_authority') ORDER BY c.relname",
                                String.class)),
                "Current permission projections must be installed.");
        assertEquals(
                Set.of(
                        "platform_role_logical_authorities",
                        "platform_role_preview_visible_organizations",
                        "platform_role_preview_visible_users"),
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON"
                                    + " n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname"
                                    + " IN ('platform_role_logical_authorities',"
                                    + "'platform_role_preview_visible_organizations','platform_role_preview_visible_users')"
                                    + " ORDER BY p.proname",
                                String.class)),
                "Current permission functions must be installed.");
        assertEquals(
                Set.of("platform_role_data_scope_check", "platform_role_auth_methods_check"),
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT conname FROM pg_constraint "
                                        + "WHERE connamespace='public'::regnamespace "
                                        + "AND conname IN ('platform_role_data_scope_check',"
                                        + "'platform_role_auth_methods_check') ORDER BY conname",
                                String.class)),
                "Current role scope and authentication constraints must be installed.");
        assertEquals(
                Set.of("platform_role_authority_pkey", "platform_user_role_pkey"),
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE schemaname='public' "
                                        + "AND indexname IN ('platform_role_authority_pkey',"
                                        + "'platform_user_role_pkey') ORDER BY indexname",
                                String.class)),
                "Current role permission uniqueness indexes must be installed.");
    }

    private static void assertNoBusinessRowsOrSecrets(JdbcTemplate jdbc) {
        for (String table : EMPTY_BUSINESS_TABLES) {
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.tables "
                                    + "WHERE table_schema='public' AND table_name=?",
                            Integer.class,
                            table),
                    "Expected B52 table is missing: " + table);
            assertEquals(
                    0L,
                    jdbc.queryForObject("SELECT count(*) FROM public." + table, Long.class),
                    "Fresh baseline must not seed business rows in " + table);
        }
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.platform_captcha_settings "
                                + "WHERE NULLIF(BTRIM(access_key_id),'') IS NOT NULL "
                                + "OR NULLIF(BTRIM(primary_app_id),'') IS NOT NULL "
                                + "OR NULLIF(BTRIM(accessible_app_id),'') IS NOT NULL",
                        Integer.class),
                "Captcha credentials must not be seeded.");
    }

    private static void assertDefaultRoleSeeds(JdbcTemplate jdbc) {
        assertEquals(
                List.of(
                        "00000000-0000-0000-0000-000000000052|admin|管理员|true|ALL|0",
                        "00000000-0000-0000-0000-000000000053|user|普通用户|true|SELF|0"),
                jdbc.queryForList(
                        "SELECT"
                            + " concat_ws('|',id::text,code,name,enabled::text,data_scope,version::text)"
                            + " FROM public.platform_role ORDER BY code,id",
                        String.class),
                "The baseline must seed only the stable administrator and ordinary roles.");

        Set<String> userGrants =
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT authority FROM public.platform_role_authority "
                                        + "WHERE role_id='00000000-0000-0000-0000-000000000053' "
                                        + "ORDER BY authority",
                                String.class));
        assertEquals(
                Set.of(
                        "platform:feedback:access",
                        "platform:feedback:read",
                        "platform:feedback:create"),
                userGrants,
                "The ordinary role must stay eligible for self-scoped registration only.");

        Set<String> administratorGrants =
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT authority FROM public.platform_role_authority "
                                        + "WHERE role_id='00000000-0000-0000-0000-000000000052' "
                                        + "ORDER BY authority",
                                String.class));
        Set<String> platformPermissions = new HashSet<>();
        for (PlatformPageId page : PlatformPageId.values())
            platformPermissions.add(page.accessPermission());
        for (PlatformOperation operation : PlatformOperation.values())
            platformPermissions.add(operation.permission());
        assertTrue(
                administratorGrants.containsAll(platformPermissions),
                "The administrator role must explicitly hold every platform page and operation.");
        assertTrue(
                administratorGrants.stream().noneMatch(authority -> authority.contains("*")),
                "The administrator role must use explicit permission rows.");

        assertEquals(
                "00000000-0000-0000-0000-000000000053",
                jdbc.queryForObject(
                        "SELECT default_role_id::text FROM public.platform_registration_policy"
                                + " WHERE id=1",
                        String.class),
                "Registration must reference the ordinary role even while disabled.");
        assertEquals(
                false,
                jdbc.queryForObject(
                        "SELECT enabled FROM public.platform_registration_policy WHERE id=1",
                        Boolean.class),
                "Public registration remains disabled in the baseline.");
    }

    private static List<String> historyRows(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT concat_ws('|', COALESCE(version,''), description, type, script,"
                    + " success::text) FROM public.flyway_schema_history ORDER BY installed_rank",
                String.class);
    }

    private static Map<String, List<String>> schemaSnapshot(JdbcTemplate jdbc) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        snapshot.put(
                "relations",
                jdbc.queryForList(
                        "SELECT"
                            + " concat_ws('|',c.relname,c.relkind,c.relpersistence,c.relrowsecurity::text,c.relforcerowsecurity::text)"
                            + " FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE"
                            + " n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f') AND"
                            + " c.relname<>'flyway_schema_history' ORDER BY c.relname",
                        String.class));
        snapshot.put(
                "columns",
                jdbc.queryForList(
                        "SELECT concat_ws('|',table_name,ordinal_position::text,column_name,"
                            + "data_type,udt_name,is_nullable,"
                            + "COALESCE(character_maximum_length::text,''),"
                            + "COALESCE(numeric_precision::text,''),COALESCE(numeric_scale::text,''),COALESCE(column_default,''))"
                            + " FROM information_schema.columns WHERE table_schema='public' AND"
                            + " table_name<>'flyway_schema_history' ORDER BY"
                            + " table_name,ordinal_position",
                        String.class));
        snapshot.put(
                "constraints",
                jdbc.queryForList(
                        "SELECT concat_ws('|',c.relname,con.conname,con.contype::text,"
                                + "pg_get_constraintdef(con.oid,true)) FROM pg_constraint con "
                                + "JOIN pg_class c ON c.oid=con.conrelid "
                                + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                                + "WHERE n.nspname='public' AND c.relname<>'flyway_schema_history' "
                                + "ORDER BY c.relname,con.conname",
                        String.class));
        snapshot.put(
                "indexes",
                jdbc.queryForList(
                        "SELECT concat_ws('|',tablename,indexname,indexdef) FROM pg_indexes WHERE"
                            + " schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY"
                            + " tablename,indexname",
                        String.class));
        snapshot.put(
                "views",
                jdbc.queryForList(
                        "SELECT concat_ws('|',c.relname,pg_get_viewdef(c.oid,true)) FROM pg_class c"
                            + " JOIN pg_namespace n ON n.oid=c.relnamespace WHERE"
                            + " n.nspname='public' AND c.relkind IN ('v','m') ORDER BY c.relname",
                        String.class));
        snapshot.put(
                "functions",
                jdbc.queryForList(
                        "SELECT"
                            + " concat_ws('|',p.proname,pg_get_function_identity_arguments(p.oid),pg_get_function_result(p.oid),pg_get_functiondef(p.oid))"
                            + " FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE"
                            + " n.nspname='public' ORDER BY"
                            + " p.proname,pg_get_function_identity_arguments(p.oid)",
                        String.class));
        snapshot.put(
                "triggers",
                jdbc.queryForList(
                        "SELECT concat_ws('|',c.relname,t.tgname,pg_get_triggerdef(t.oid,true))"
                            + " FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN"
                            + " pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND"
                            + " NOT t.tgisinternal ORDER BY c.relname,t.tgname",
                        String.class));
        return snapshot;
    }

    private static Map<String, List<String>> stableSeedSnapshot(JdbcTemplate jdbc) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, String> seed : STABLE_SEED_QUERIES.entrySet()) {
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.tables "
                                    + "WHERE table_schema='public' AND table_name=?",
                            Integer.class,
                            seed.getKey()),
                    "Stable seed table is missing: " + seed.getKey());
            snapshot.put(seed.getKey(), jdbc.queryForList(seed.getValue(), String.class));
        }
        return snapshot;
    }

    private static void assertSnapshotEquals(
            Map<String, List<String>> expected, Map<String, List<String>> actual, String message) {
        assertEquals(expected.keySet(), actual.keySet(), message + " Snapshot categories differ.");
        for (String category : expected.keySet()) {
            List<String> expectedRows = expected.get(category);
            List<String> actualRows = actual.get(category);
            assertEquals(expectedRows.size(), actualRows.size(), message + " Category " + category);
            for (int index = 0; index < expectedRows.size(); index++) {
                assertEquals(
                        expectedRows.get(index),
                        actualRows.get(index),
                        message + " Category " + category + " row " + index);
            }
        }
    }

    private static void executeBaselineSql(DriverManagerDataSource dataSource, String source) {
        String url = dataSource.getUrl();
        String simpleProtocolUrl = url + (url.contains("?") ? "&" : "?") + "preferQueryMode=simple";
        DriverManagerDataSource simpleDataSource =
                new DriverManagerDataSource(
                        simpleProtocolUrl, dataSource.getUsername(), dataSource.getPassword());
        try (var connection = simpleDataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(source);
        } catch (Exception error) {
            throw new IllegalStateException("Could not import the exact B52 SQL script", error);
        }
    }

    private static String baselineSql() throws IOException {
        try (InputStream input =
                DatabaseBaselineMigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream("db/migration/" + BASELINE_SCRIPT)) {
            assertNotNull(input, "B52 must be packaged at the production Flyway location.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Missing explicit isolated test variable " + name);
        assertFalse(value.isBlank(), "Missing explicit isolated test variable " + name);
        return value;
    }
}
