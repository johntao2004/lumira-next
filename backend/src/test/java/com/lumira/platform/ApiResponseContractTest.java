package com.lumira.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.PageResult;
import com.lumira.plugins.directory.roles.RoleRepository;
import com.lumira.plugins.directory.roles.api.RoleResponse;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ApiResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String[]> CONTRACTS =
            List.of(
                    new String[] {
                        "com.lumira.plugins.directory.organizations.OrganizationRepository$Organization",
                        "com.lumira.plugins.directory.organizations.api.OrganizationResponse"
                    },
                    new String[] {
                        "com.lumira.plugins.directory.sessions.OnlineSessionRepository$Entry",
                        "com.lumira.plugins.directory.sessions.api.OnlineSessionResponse"
                    },
                    new String[] {
                        "com.lumira.platform.identity.AccountSessionRepository$Session",
                        "com.lumira.platform.identity.api.SessionResponse"
                    },
                    new String[] {
                        "com.lumira.platform.navigation.api.NavigationMenu",
                        "com.lumira.platform.navigation.api.NavigationMenuResponse"
                    },
                    new String[] {
                        "com.lumira.plugins.directory.roles.RoleRepository$Role",
                        "com.lumira.plugins.directory.roles.api.RoleResponse"
                    },
                    new String[] {
                        "com.lumira.platform.common.AuditQueryPort$Event",
                        "com.lumira.plugins.auditconsole.api.AuditEventResponse"
                    },
                    new String[] {
                        "com.lumira.platform.common.AccountFileStoragePort$Entry",
                        "com.lumira.plugins.personalfiles.FileResponse"
                    },
                    new String[] {
                        "com.lumira.platform.files.AvatarService$AvatarView",
                        "com.lumira.platform.files.api.AvatarResponse"
                    },
                    new String[] {
                        "com.lumira.plugins.fileconsole.ManagedFileRepository$Entry",
                        "com.lumira.plugins.fileconsole.ManagedFileResponse"
                    },
                    new String[] {
                        "com.lumira.plugins.dictionaries.DictionaryItemRepository$Item",
                        "com.lumira.plugins.dictionaries.api.DictionaryItemResponse"
                    },
                    new String[] {
                        "com.lumira.plugins.dictionaries.DictionaryRepository$Dictionary",
                        "com.lumira.plugins.dictionaries.api.DictionaryResponse"
                    },
                    new String[] {
                        "com.lumira.platform.sensitive.SensitiveWordRepository$Word",
                        "com.lumira.platform.sensitive.api.SensitiveWordResponse"
                    },
                    new String[] {
                        "com.lumira.platform.sensitive.RoleContentPolicyRepository$Policy",
                        "com.lumira.platform.sensitive.api.RoleContentPolicyResponse"
                    },
                    new String[] {
                        "com.lumira.platform.sensitive.RoleContentPolicyRepository$Hit",
                        "com.lumira.platform.sensitive.api.ContentPolicyHitResponse"
                    },
                    new String[] {
                        "com.lumira.platform.localization.LocalizationRepository$Entry",
                        "com.lumira.platform.localization.api.LocalizationEntryResponse"
                    });

    @Test
    void explicitResponsesPreserveAllExistingJsonFieldsIncludingNulls() throws Exception {
        for (var pair : CONTRACTS) {
            Class<?> source = Class.forName(pair[0]);
            Class<?> response = Class.forName(pair[1]);
            var convert = response.getMethod("from", source);
            for (boolean nullable : List.of(false, true)) {
                RecordComponent[] fields = source.getRecordComponents();
                var types =
                        Arrays.stream(fields)
                                .map(RecordComponent::getType)
                                .toArray(Class<?>[]::new);
                var values =
                        Arrays.stream(fields)
                                .map(field -> sample(field.getType(), nullable))
                                .toArray();
                Object value = source.getDeclaredConstructor(types).newInstance(values);
                Object dto = convert.invoke(null, value);
                Object expected = mapper.valueToTree(value);
                if (response == RoleResponse.class)
                    expectedRoleResponseFields((RoleRepository.Role) value, expected);
                assertEquals(expected, mapper.valueToTree(dto), response.getName());
            }
            assertNull(convert.invoke(null, new Object[] {null}));
        }
    }

    @Test
    void personalFileStorageMetadataRetainsEveryLegacyFieldAndType() {
        assertEquals(
                Arrays.stream(
                                com.lumira.platform.files.FileRepository.Entry.class
                                        .getRecordComponents())
                        .map(field -> field.getName() + ":" + field.getType().getName())
                        .toList(),
                Arrays.stream(
                                com.lumira.platform.common.AccountFileStoragePort.Entry.class
                                        .getRecordComponents())
                        .map(field -> field.getName() + ":" + field.getType().getName())
                        .toList());
    }

    @Test
    void mappingDoesNotResetPageMetadataOrLoseEmptyResults() {
        assertEquals(
                new PageResult<>(List.of("7"), 90, 3, 25),
                new PageResult<>(List.of(7), 90, 3, 25).map(Object::toString));
        assertEquals(
                new PageResult<>(List.of(), 90, 4, 25),
                new PageResult<Integer>(List.of(), 90, 4, 25).map(Object::toString));
    }

    @Test
    void roleResponseDoesNotExposeRetiredProjectionMetadata() {
        assertFalse(
                Arrays.stream(RoleResponse.class.getRecordComponents())
                        .anyMatch(
                                component ->
                                        component.getName().equals("legacyProjectionPermissions")));
    }

    private static Object sample(Class<?> type, boolean nullable) {
        if (nullable && !type.isPrimitive()) return null;
        if (type == String.class) return "example";
        if (type == UUID.class) return UUID.fromString("11111111-1111-1111-1111-111111111111");
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 7;
        if (type == long.class || type == Long.class) return 7L;
        if (type == Instant.class) return Instant.parse("2026-01-01T00:00:00Z");
        if (type == OffsetDateTime.class) return OffsetDateTime.parse("2026-01-01T00:00:00Z");
        if (type == List.class) return List.of("example");
        if (type == Map.class) return Map.of("en", "example");
        throw new AssertionError("Add a representative value for " + type);
    }

    private void expectedRoleResponseFields(RoleRepository.Role role, Object expected) {
        ObjectNode json = (ObjectNode) expected;
        json.set(
                "authorities",
                mapper.valueToTree(role.authorities() == null ? List.of() : role.authorities()));
    }
}
