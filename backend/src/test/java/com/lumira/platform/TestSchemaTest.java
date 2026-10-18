package com.lumira.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TestSchemaTest {
    private static final String ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000052";
    private static final String USER_ROLE_ID = "00000000-0000-0000-0000-000000000053";

    @Test
    void extractsMultilineDefaultRoleSeedsAndCurrentAuthorities() {
        List<String> roles = TestSchema.seeds("platform_role");
        assertEquals(1, roles.size());
        String roleSeed = roles.getFirst();
        assertEquals(
                2,
                count(
                        roleSeed,
                        Pattern.compile("(?m)^\\('00000000-0000-0000-0000-00000000005[23]'")));
        assertTrue(roleSeed.contains(ADMIN_ROLE_ID + "', 'admin', '管理员'"));
        assertTrue(roleSeed.contains(USER_ROLE_ID + "', 'user', '普通用户'"));
        assertFalse(roleSeed.contains("public."));

        List<String> grants = TestSchema.seeds("platform_role_authority");
        assertEquals(1, grants.size());
        String grantSeed = grants.getFirst();
        assertEquals(
                148,
                count(
                        grantSeed,
                        Pattern.compile("(?m)^\\('00000000-0000-0000-0000-00000000005[23]'")));
        assertEquals(
                145,
                count(grantSeed, Pattern.compile("(?m)^\\('" + ADMIN_ROLE_ID + "', 'platform:")));
        assertEquals(
                3, count(grantSeed, Pattern.compile("(?m)^\\('" + USER_ROLE_ID + "', 'platform:")));
        assertTrue(
                grantSeed.contains("('" + ADMIN_ROLE_ID + "', 'platform:files-all:update-quota')"));
        assertFalse(
                grantSeed.contains("('" + USER_ROLE_ID + "', 'platform:files-all:update-quota')"));
        assertFalse(grantSeed.contains("public."));
    }

    @Test
    void parsesOnlyTopLevelInsertsAndKeepsStatementsSeparate() {
        String source =
                """
                -- The first seed spans lines and contains semicolons in SQL strings.
                INSERT INTO public.platform_role (id, name) VALUES
                    ('first', 'semicolon; inside text'),
                    ('second', 'escaped quote '' and another ;');
                CREATE FUNCTION public.seed_hidden_role() RETURNS void AS $function$
                BEGIN
                    INSERT INTO public.platform_role (id, name) VALUES ('hidden', 'function body');
                END;
                $function$ LANGUAGE plpgsql;
                INSERT INTO public.platform_role (id, name) VALUES ('third', E'escaped \\' quote; text');
                INSERT INTO public.platform_role_extra (id, name) VALUES ('other', 'not requested');
                """;

        List<String> statements = TestSchema.seedStatements("platform_role", source);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("('first', 'semicolon; inside text')"));
        assertTrue(statements.get(0).contains("('second', 'escaped quote '' and another ;')"));
        assertFalse(statements.get(0).contains("'third'"));
        assertFalse(statements.get(0).contains("'hidden'"));
        assertTrue(statements.get(1).contains("('third', E'escaped \\' quote; text')"));
        assertFalse(statements.get(1).contains("'other'"));
    }

    private static int count(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        int matches = 0;
        while (matcher.find()) matches++;
        return matches;
    }
}
