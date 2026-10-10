package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Publishes only the public registration entry point and its currently safe channels. */
@Service
public class RegistrationCapabilityContributor implements AuthenticationCapabilityContributor {
    private static final String REGISTRATION_PATH = "/user/register";
    private static final String ACCOUNT_METHODS_REVISION = "registration-capability:v1";
    private static final Set<String> PUBLIC_CHANNELS = Set.of("EMAIL", "SMS");

    private final PublicRegistrationService registration;

    public RegistrationCapabilityContributor(PublicRegistrationService registration) {
        this.registration = registration;
    }

    @Override
    public String id() {
        return "public-registration";
    }

    @Override
    public Contribution authenticationCapabilities() {
        PublicRegistrationService.Config config = registration.config();
        List<String> channels =
                config.channels().stream()
                        .filter(PUBLIC_CHANNELS::contains)
                        .distinct()
                        .sorted()
                        .toList();
        return new Contribution(
                Set.of(),
                false,
                false,
                false,
                false,
                new Registration(
                        true,
                        config.available(),
                        config.available() ? channels : List.of(),
                        REGISTRATION_PATH),
                null);
    }

    /** Registration settings do not change an existing account's authentication state. */
    @Override
    public AccountMethods accountMethods(UUID userId) {
        return new AccountMethods(Set.of(), ACCOUNT_METHODS_REVISION);
    }
}
