package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class LoginCodeCapabilityContributorTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID credentialRevision = UUID.randomUUID();
    private final AuthenticationMethodAvailabilityPort availability =
            mock(AuthenticationMethodAvailabilityPort.class);

    @Test
    void absentContactsPreserveRevisionWithoutConsultingProviders() throws Exception {
        var contributor = contributor(null, false, null, null, false, null);

        var result = contributor.accountMethods(userId);

        assertEquals(Set.of(), result.usableMethods());
        assertEquals(
                credentialRevision + ":email=null/false:phone=null/false",
                result.authenticationRevision());
        verifyNoInteractions(availability);
    }

    @Test
    void unverifiedAndBlankContactsCannotBecomeUsableThroughConfiguredProviders() throws Exception {
        var unverified = contributor("account@example.test", false, 3L, "+12025550123", false, 8L);
        var blank = contributor(" ", true, 4L, "", true, 9L);

        var unverifiedResult = unverified.accountMethods(userId);
        var blankResult = blank.accountMethods(userId);

        assertEquals(Set.of(), unverifiedResult.usableMethods());
        assertEquals(
                credentialRevision + ":email=3/false:phone=8/false",
                unverifiedResult.authenticationRevision());
        assertEquals(Set.of(), blankResult.usableMethods());
        assertEquals(
                credentialRevision + ":email=4/true:phone=9/true",
                blankResult.authenticationRevision());
        verifyNoInteractions(availability);
    }

    @Test
    void eligibleContactsStillObserveProviderRevocationOnEveryResolution() throws Exception {
        var contributor = contributor("account@example.test", true, 3L, "+12025550123", true, 8L);
        when(availability.availableMethods())
                .thenReturn(Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP), Set.of());

        var before = contributor.accountMethods(userId);
        var after = contributor.accountMethods(userId);

        assertEquals(
                Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP), before.usableMethods());
        assertEquals(Set.of(), after.usableMethods());
        assertEquals(
                credentialRevision + ":email=3/true:phone=8/true", before.authenticationRevision());
        assertEquals(before.authenticationRevision(), after.authenticationRevision());
        verify(availability, times(2)).availableMethods();
    }

    @Test
    void oneVerifiedContactNeverEnablesTheOtherUnverifiedContact() throws Exception {
        var contributor = contributor("account@example.test", false, 3L, "+12025550123", true, 8L);
        when(availability.availableMethods())
                .thenReturn(Set.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.SMS_OTP));

        assertEquals(
                Set.of(PrimaryMethod.SMS_OTP), contributor.accountMethods(userId).usableMethods());
        verify(availability).availableMethods();
    }

    @Test
    void providerInfrastructureFailureStillFailsClosedForAnEligibleContact() throws Exception {
        var contributor = contributor("account@example.test", true, 3L, null, false, null);
        when(availability.availableMethods()).thenThrow(new IllegalStateException("unavailable"));

        assertThrows(IllegalStateException.class, () -> contributor.accountMethods(userId));
        verify(availability).availableMethods();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LoginCodeCapabilityContributor contributor(
            String email,
            boolean emailVerified,
            Long emailVersion,
            String phone,
            boolean phoneVerified,
            Long phoneVersion)
            throws Exception {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        var row = mock(ResultSet.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("id", userId)).thenReturn(statement);
        when(row.getObject("primary_credential_revision", UUID.class))
                .thenReturn(credentialRevision);
        when(row.getString("email")).thenReturn(email);
        when(row.getObject("email_verified", OffsetDateTime.class))
                .thenReturn(emailVerified ? OffsetDateTime.parse("2026-01-01T00:00:00Z") : null);
        when(row.getObject("email_version")).thenReturn(emailVersion);
        when(row.getString("phone")).thenReturn(phone);
        when(row.getObject("phone_verified", OffsetDateTime.class))
                .thenReturn(phoneVerified ? OffsetDateTime.parse("2026-01-01T00:00:00Z") : null);
        when(row.getObject("phone_version")).thenReturn(phoneVersion);
        doAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(0);
                            var mapped = Optional.of(mapper.mapRow(row, 0));
                            when(query.optional()).thenReturn(mapped);
                            return query;
                        })
                .when(statement)
                .query(any(RowMapper.class));
        return new LoginCodeCapabilityContributor(jdbc, availability);
    }
}
