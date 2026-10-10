package com.lumira.platform.common;

import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/** Shared CSRF header contract used by the security filter and API documentation. */
public final class CsrfTokenContract {
    public static final String HEADER_NAME = "X-CSRF-TOKEN";

    private CsrfTokenContract() {}

    public static HttpSessionCsrfTokenRepository sessionRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName(HEADER_NAME);
        return repository;
    }
}
