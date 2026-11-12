package com.lumira.platform.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** User details for password-provider proof capture; credential erasure remains enabled. */
final class PasswordProofUserDetails
        implements UserDetails, CredentialsContainer, java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final String username;
    private String password;
    private final boolean enabled;
    private final UUID primaryCredentialRevision;
    private final List<GrantedAuthority> authorities;

    PasswordProofUserDetails(
            String username,
            String password,
            boolean enabled,
            UUID primaryCredentialRevision,
            Collection<? extends GrantedAuthority> authorities) {
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.primaryCredentialRevision = primaryCredentialRevision;
        this.authorities = List.copyOf(authorities);
    }

    UUID primaryCredentialRevision() {
        return primaryCredentialRevision;
    }

    /**
     * Spring Security keeps the loaded user as the authentication principal and only replaces the
     * object returned by {@code UserDetailsPasswordService}. The password proof is read from that
     * principal after a legacy hash has already been replaced in the database, so the same instance
     * must observe the upgraded encoding.
     */
    void useEncodedPassword(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("Upgraded password encoding is required");
        }
        this.password = encodedPassword;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }
}
