package com.lumira.platform.identity;

import com.lumira.platform.common.MailSender;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared normalization for user-supplied contacts in anonymous identity flows. */
final class PublicAuthContactNormalizer {
    enum Flow {
        REGISTRATION("invalidRegistrationContact"),
        LOGIN_CODE("invalidLoginCodeContact");

        private final String invalidReason;

        Flow(String invalidReason) {
            this.invalidReason = invalidReason;
        }
    }

    private PublicAuthContactNormalizer() {}

    static String normalize(String channel, String raw, MailSender mail, Flow flow) {
        if (raw == null || raw.isBlank() || raw.length() > 320) throw invalid(flow);
        if ("EMAIL".equals(channel)) {
            String email = raw.strip();
            try {
                mail.validateRecipient(email);
            } catch (RuntimeException invalid) {
                throw invalid(flow);
            }
            if (email.length() > 254) throw invalid(flow);
            return email.toLowerCase(Locale.ROOT);
        }
        try {
            return AccountPhoneService.normalize(raw);
        } catch (ResponseStatusException invalid) {
            throw invalid(flow);
        }
    }

    private static ResponseStatusException invalid(Flow flow) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, flow.invalidReason);
    }
}
