package com.lumira.platform.identity;

import com.lumira.platform.identity.api.AccountProvisioningPort;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Trusted module adapter; anonymous HTTP callers never reach the administrator user controller. */
@Service
public class AccountProvisioningAdapter implements AccountProvisioningPort {
    private final AccountRegistrationRepository users;
    private final PasswordEncoder encoder;
    private final SecurityPolicyPort security;

    public AccountProvisioningAdapter(
            AccountRegistrationRepository users,
            PasswordEncoder encoder,
            SecurityPolicyPort security) {
        this.users = users;
        this.encoder = encoder;
        this.security = security;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
    public PreparedPassword preparePassword(String rawPassword) {
        if (rawPassword == null
                || !security.currentPolicy().passwordValid(rawPassword)
                || !PasswordContract.withinMaximum(rawPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passwordPolicy");
        }
        return new PreparedPassword(encoder.encode(rawPassword));
    }

    @Override
    @Transactional
    public UUID provision(String username, String displayName, PreparedPassword password) {
        if (username == null
                || !username.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")
                || displayName == null
                || displayName.strip().isEmpty()
                || displayName.strip().length() > 200
                || password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRegistration");
        }
        UUID id = UUID.randomUUID();
        if (!users.create(id, username, displayName.strip(), password.encodedValue()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "registrationUsernameTaken");
        return id;
    }
}
