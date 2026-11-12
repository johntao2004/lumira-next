package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
    @Test
    void onlyPluginFencesAreReportedAsModuleUnavailable() {
        var handler = new ApiExceptionHandler();
        var problem =
                handler.infrastructure(
                        new org.springframework.dao.CannotSerializeTransactionException(
                                "private SQL", new PluginWorkFencedException()));
        assertEquals(503, problem.getStatus());
        assertEquals("moduleUnavailable", problem.getDetail());
        assertEquals(
                500,
                handler.infrastructure(
                                new java.sql.SQLException("other serialization failure", "40001"))
                        .getStatus());
    }

    @Test
    void infrastructureErrorsDoNotExposeDetails() {
        var handler = new ApiExceptionHandler();
        for (Exception error :
                java.util.List.of(
                        new java.io.IOException("private path"),
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "private SQL"),
                        new org.springframework.transaction.TransactionSystemException(
                                "private database"))) {
            var problem = handler.infrastructure(error);
            assertEquals(500, problem.getStatus());
            assertEquals("unavailable", problem.getDetail());
        }
    }

    @Test
    void passwordHashCapacityUsesOneSecondRetryAcrossExceptionPaths() {
        var handler = new ApiExceptionHandler();

        var rateLimit = handler.rateLimit(new RateLimitExceededException("passwordHashCapacity"));
        assertEquals(429, rateLimit.getStatusCode().value());
        assertEquals("1", rateLimit.getHeaders().getFirst("Retry-After"));
        assertEquals("passwordHashCapacity", rateLimit.getBody().getDetail());

        var status =
                handler.status(
                        new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                                "passwordHashCapacity"));
        assertEquals(429, status.getStatusCode().value());
        assertEquals("1", status.getHeaders().getFirst("Retry-After"));
        assertEquals("passwordHashCapacity", status.getBody().getDetail());
    }

    @Test
    void otherRateLimitsKeepExistingRetryBehavior() {
        var handler = new ApiExceptionHandler();
        var rateLimit = handler.rateLimit(new RateLimitExceededException("uploadRateLimited"));
        assertEquals("60", rateLimit.getHeaders().getFirst("Retry-After"));

        var status =
                handler.status(
                        new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.CONFLICT,
                                "authorizationContextChanged"));
        assertEquals(409, status.getStatusCode().value());
        assertNull(status.getHeaders().getFirst("Retry-After"));
        assertEquals("authorizationContextChanged", status.getBody().getDetail());
    }
}
