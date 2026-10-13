package com.lumira.platform.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises V47 against an existing, upgradeable navigation tree in a disposable database. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_NAVIGATION_MIGRATION_TEST_DB_URL", matches = ".+")
class NavigationGroupingMigrationTest {
    @Test
    void v47MovesDefaultNavigationWithoutChangingCustomMenusOrUserState() {
        String url = System.getenv("LUMIRA_NAVIGATION_MIGRATION_TEST_DB_URL");
        String user = System.getenv("LUMIRA_NAVIGATION_MIGRATION_TEST_DB_USER");
        String password = System.getenv("LUMIRA_NAVIGATION_MIGRATION_TEST_DB_PASSWORD");
        var dataSource = new DriverManagerDataSource(url, user, password);
        var jdbc = new JdbcTemplate(dataSource);

        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(
                database != null && database.startsWith("lumira_navigation_qa_"),
                "Navigation migration tests require a dedicated lumira_navigation_qa_* database.");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
                        Integer.class),
                "The dedicated navigation migration database must be fresh.");

        var beforeV47 =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .target("46")
                        .load();
        beforeV47.migrate();

        jdbc.update(
                "INSERT INTO platform_navigation_menu "
                        + "(id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,"
                        + "permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version) "
                        + "VALUES ('10000000-0000-0000-0000-000000000001',NULL,'custom-bucket',"
                        + "'customBucket','自定义分组','CATALOG',NULL,NULL,NULL,false,NULL,10,true,false,false,1),"
                        + "('10000000-0000-0000-0000-000000000002',"
                        + "'10000000-0000-0000-0000-000000000001','custom-page','customPage','自定义页面',"
                        + "'PAGE','custom-page','/dashboard/custom-page',NULL,false,NULL,12,false,false,false,1)");
        jdbc.update(
                "UPDATE platform_navigation_menu SET parent_id='10000000-0000-0000-0000-000000000001', "
                        + "sort_order=7 WHERE menu_code='branding' AND built_in=true");
        jdbc.update(
                "UPDATE platform_navigation_menu SET enabled=false WHERE menu_code='smtp' AND built_in=true");
        jdbc.update(
                "UPDATE platform_navigation_menu SET enabled=false,deleted=true "
                        + "WHERE menu_code='security' AND built_in=true");

        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000001",
                "mfa-authenticator",
                "mfa",
                "overview",
                null,
                false,
                30);
        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000002",
                "mfa-management",
                "mfa",
                "administration",
                "platform:mfa:manage",
                true,
                210);
        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000003",
                "payment-core-orders",
                "payment-core",
                "operations",
                "platform:payment-core:read",
                true,
                221);
        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000004",
                "wechat-pay-orders",
                "wechat-pay",
                "operations",
                null,
                false,
                119);
        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000005",
                "wechat-pay-settings",
                "wechat-pay",
                "administration",
                null,
                false,
                249);
        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000006",
                "alipay-pay-orders",
                "alipay-pay",
                "operations",
                null,
                false,
                120);
        insertPluginPage(
                jdbc,
                "20000000-0000-0000-0000-000000000007",
                "alipay-pay-settings",
                "alipay-pay",
                "administration",
                null,
                false,
                250);

        var current =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .target("47")
                        .load();
        jdbc.update(
                "INSERT INTO platform_navigation_menu "
                        + "(id,parent_id,menu_code,label_key,label_default,menu_type,sort_order,enabled,built_in,deleted,version) "
                        + "VALUES ('30000000-0000-0000-0000-000000000001',NULL,'communication',"
                        + "'customCommunication','管理员目录','CATALOG',99,true,false,false,1)");
        assertThrows(FlywayException.class, current::migrate);
        assertEquals(
                "customCommunication",
                jdbc.queryForObject(
                        "SELECT label_key FROM platform_navigation_menu WHERE menu_code='communication'",
                        String.class));
        jdbc.update("DELETE FROM platform_navigation_menu WHERE menu_code='communication'");
        current.migrate();

        assertEquals("47", current.info().current().getVersion().getVersion());
        assertEquals("communication", parentCode(jdbc, "notifications"));
        assertEquals("communication", parentCode(jdbc, "smtp"));
        assertEquals("user-management", parentCode(jdbc, "security"));
        assertEquals("communication", parentCode(jdbc, "feedback"));
        assertEquals("payments", parentCode(jdbc, "payment-core-orders"));
        assertEquals("payments", parentCode(jdbc, "wechat-pay-orders"));
        assertEquals("payments", parentCode(jdbc, "wechat-pay-settings"));
        assertEquals("payments", parentCode(jdbc, "alipay-pay-orders"));
        assertEquals("payments", parentCode(jdbc, "alipay-pay-settings"));
        assertEquals("user-management", parentCode(jdbc, "mfa-authenticator"));
        assertEquals("user-management", parentCode(jdbc, "mfa-management"));
        assertEquals("custom-bucket", parentCode(jdbc, "branding"));
        assertEquals(
                7,
                jdbc.queryForObject(
                        "SELECT sort_order FROM platform_navigation_menu WHERE menu_code='branding'",
                        Integer.class));
        assertEquals(
                "siteSettings",
                jdbc.queryForObject(
                        "SELECT label_key FROM platform_navigation_menu WHERE menu_code='branding'",
                        String.class));
        assertEquals(
                "站点设置",
                jdbc.queryForObject(
                        "SELECT label_default FROM platform_navigation_menu WHERE menu_code='branding'",
                        String.class));

        var smtp =
                jdbc.queryForMap(
                        "SELECT enabled,deleted FROM platform_navigation_menu WHERE menu_code='smtp'");
        assertEquals(false, smtp.get("enabled"));
        assertEquals(false, smtp.get("deleted"));
        var security =
                jdbc.queryForMap(
                        "SELECT enabled,deleted FROM platform_navigation_menu WHERE menu_code='security'");
        assertEquals(false, security.get("enabled"));
        assertEquals(true, security.get("deleted"));

        for (String route :
                List.of(
                        "wechat-pay-orders",
                        "wechat-pay-settings",
                        "alipay-pay-orders",
                        "alipay-pay-settings")) {
            assertEquals(
                    "platform:payment-core:read",
                    jdbc.queryForObject(
                            "SELECT permission_key FROM platform_navigation_menu WHERE menu_code=?",
                            String.class,
                            route));
            assertTrue(
                    jdbc.queryForObject(
                            "SELECT global_only FROM platform_navigation_menu WHERE menu_code=?",
                            Boolean.class,
                            route));
        }

        var custom =
                jdbc.queryForMap(
                        "SELECT parent_id,label_key,label_default,sort_order,enabled,built_in,deleted,version "
                                + "FROM platform_navigation_menu WHERE menu_code='custom-page'");
        assertEquals(
                java.util.UUID.fromString("10000000-0000-0000-0000-000000000001"),
                custom.get("parent_id"));
        assertEquals("customPage", custom.get("label_key"));
        assertEquals("自定义页面", custom.get("label_default"));
        assertEquals(12, custom.get("sort_order"));
        assertEquals(false, custom.get("enabled"));
        assertEquals(false, custom.get("built_in"));
        assertEquals(false, custom.get("deleted"));
        assertEquals(1L, custom.get("version"));
    }

    private static void insertPluginPage(
            JdbcTemplate jdbc,
            String id,
            String route,
            String module,
            String parent,
            String permission,
            boolean globalOnly,
            int order) {
        jdbc.update(
                "INSERT INTO platform_navigation_menu "
                        + "(id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,"
                        + "permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version) "
                        + "SELECT ?,parent.id,?,?,?,'PAGE',?,?,?,?,?,?,true,false,false,1 "
                        + "FROM platform_navigation_menu parent WHERE parent.menu_code=?",
                java.util.UUID.fromString(id),
                route,
                route,
                route,
                route,
                "/dashboard/" + route,
                permission,
                globalOnly,
                module,
                order,
                parent);
    }

    private static String parentCode(JdbcTemplate jdbc, String menuCode) {
        return jdbc.queryForObject(
                "SELECT parent.menu_code FROM platform_navigation_menu page "
                        + "JOIN platform_navigation_menu parent ON parent.id=page.parent_id "
                        + "WHERE page.menu_code=?",
                String.class,
                menuCode);
    }
}
