package com.lumira.platform.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;

/** Captures only a keyed hash fingerprint before ProviderManager erases provider credentials. */
final class PasswordProofAuthenticationProvider implements AuthenticationProvider {
    private final AuthenticationProvider delegate;
    private final PasswordCredentialProof proofs;

    PasswordProofAuthenticationProvider(
            AuthenticationProvider delegate, PasswordCredentialProof proofs) {
        this.delegate = delegate;
        this.proofs = proofs;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!PasswordHashCapacity.tryAcquire()) throw new CapacityException();
        try {
            Authentication result = delegate.authenticate(authentication);
            if (result instanceof AbstractAuthenticationToken token
                    && result.getPrincipal() instanceof PasswordProofUserDetails details) {
                token.setDetails(
                        proofs.capture(details.getPassword(), details.primaryCredentialRevision()));
            }
            return result;
        } finally {
            PasswordHashCapacity.release();
        }
    }

    static final class CapacityException extends AuthenticationServiceException {
        CapacityException() {
            super("passwordHashCapacity");
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
