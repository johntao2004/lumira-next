package com.lumira.platform.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record Agreements(String userAgreementMarkdown, String privacyAgreementMarkdown) {
    public static Agreements defaults() {
        return new Agreements("", "");
    }

    public void validate() {
        if (userAgreementMarkdown == null
                || privacyAgreementMarkdown == null
                || userAgreementMarkdown.length() > 100000
                || privacyAgreementMarkdown.length() > 100000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidAgreements");
    }
}
