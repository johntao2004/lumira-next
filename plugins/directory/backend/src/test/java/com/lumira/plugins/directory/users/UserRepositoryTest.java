package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserRepositoryTest {
    @Test
    void mapsSharedUserProjectionIncludingNullableQuotaOverrides() throws Exception {
        UUID id = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(id);
        when(row.getString("username")).thenReturn("qa-user");
        when(row.getString("display_name")).thenReturn("QA User");
        when(row.getBoolean("enabled")).thenReturn(true);
        when(row.getLong("version")).thenReturn(4L);
        when(row.getObject("file_quota_bytes_override", Long.class)).thenReturn(null);
        when(row.getObject("file_quota_files_override", Long.class)).thenReturn(25L);

        assertEquals(
                new UserSummary(id, "qa-user", "QA User", true, 4, null, 25L),
                UserRepository.mapSummary(row, 1));
    }
}
