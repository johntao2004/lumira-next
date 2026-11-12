package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.WechatConfigurationPort;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class WechatAccountCapabilityTest {
    private final UUID userId = UUID.randomUUID();
    private final WechatConfigurationPort settings = mock(WechatConfigurationPort.class);

    @Test
    void neverBoundAccountPreservesZeroRevisionWithoutReadingSettings() throws Exception {
        var fixture = fixture(false, 0, null, null);

        var result = fixture.service().accountMethods(userId);

        assertEquals(Set.of(), result.usableMethods());
        assertEquals("wechat-binding:0", result.authenticationRevision());
        verifyNoInteractions(settings, fixture.accountQuery());
    }

    @Test
    void removedBindingPreservesItsRevisionWithoutReadingSettings() throws Exception {
        var fixture = fixture(true, 7, null, null);

        var result = fixture.service().accountMethods(userId);

        assertEquals(Set.of(), result.usableMethods());
        assertEquals("wechat-binding:7", result.authenticationRevision());
        verifyNoInteractions(settings, fixture.accountQuery());
    }

    @Test
    void boundAccountObservesProviderDisableOnEveryResolution() throws Exception {
        var fixture = fixture(true, 7, "app-current", "binding-digest");
        when(settings.snapshot()).thenReturn(configuration(true), configuration(false));
        when(fixture.accountQuery().single()).thenReturn(true);

        var before = fixture.service().accountMethods(userId);
        var after = fixture.service().accountMethods(userId);

        assertEquals(Set.of(PrimaryMethod.WECHAT), before.usableMethods());
        assertEquals(Set.of(), after.usableMethods());
        assertEquals("wechat-binding:7", before.authenticationRevision());
        assertEquals(before.authenticationRevision(), after.authenticationRevision());
        verify(settings, times(2)).snapshot();
        verify(fixture.accountQuery()).single();
    }

    @Test
    void providerMatchDoesNotAdmitAnInactiveAccount() throws Exception {
        var fixture = fixture(true, 7, "app-current", "binding-digest");
        when(settings.snapshot()).thenReturn(configuration(true));
        when(fixture.accountQuery().single()).thenReturn(false);

        assertEquals(Set.of(), fixture.service().accountMethods(userId).usableMethods());
        verify(fixture.accountQuery()).single();
    }

    @Test
    void boundAccountStillPropagatesProviderReadFailure() throws Exception {
        var fixture = fixture(true, 7, "app-current", "binding-digest");
        when(settings.snapshot()).thenThrow(new IllegalStateException("unavailable"));

        assertThrows(IllegalStateException.class, () -> fixture.service().accountMethods(userId));
        verify(settings).snapshot();
    }

    private WechatConfigurationPort.Snapshot configuration(boolean ready) {
        return new WechatConfigurationPort.Snapshot(
                1, ready, "app-current", "https://example.test", 5, true, ready);
    }

    private record Fixture(
            WechatIdentityService service, JdbcClient.MappedQuerySpec<Boolean> accountQuery) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Fixture fixture(boolean exists, long version, String appId, String digest)
            throws Exception {
        var jdbc = mock(JdbcClient.class);
        var bindingStatement = mock(JdbcClient.StatementSpec.class);
        var bindingQuery = mock(JdbcClient.MappedQuerySpec.class);
        var accountStatement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Boolean> accountQuery = mock(JdbcClient.MappedQuerySpec.class);
        var row = mock(ResultSet.class);
        when(jdbc.sql(startsWith("SELECT version,app_id,open_id_digest,nickname")))
                .thenReturn(bindingStatement);
        when(bindingStatement.param("user", userId)).thenReturn(bindingStatement);
        when(row.getLong("version")).thenReturn(version);
        when(row.getString("app_id")).thenReturn(appId);
        when(row.getString("open_id_digest")).thenReturn(digest);
        doAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(0);
                            var mapped =
                                    exists ? Optional.of(mapper.mapRow(row, 0)) : Optional.empty();
                            when(bindingQuery.optional()).thenReturn(mapped);
                            return bindingQuery;
                        })
                .when(bindingStatement)
                .query(any(RowMapper.class));
        when(jdbc.sql(startsWith("SELECT EXISTS(SELECT 1 FROM platform_user")))
                .thenReturn(accountStatement);
        when(accountStatement.param("id", userId)).thenReturn(accountStatement);
        when(accountStatement.query(Boolean.class)).thenReturn(accountQuery);
        var service =
                new WechatIdentityService(
                        jdbc, null, null, null, null, null, settings, null, null, null, null, null,
                        null, null, null, null, null);
        return new Fixture(service, accountQuery);
    }
}
