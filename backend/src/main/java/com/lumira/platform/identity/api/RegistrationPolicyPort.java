package com.lumira.platform.identity.api;

import java.util.Set;
import java.util.UUID;

/** Settings-owned, current public-registration policy consumed by identity transactions. */
public interface RegistrationPolicyPort {
    Policy current();

    /** Lock the current singleton row for the caller's transaction (registration creation). */
    Policy lockCurrent();

    enum Channel {
        EMAIL,
        SMS
    }

    record Policy(long version, boolean enabled, Set<Channel> channels, UUID defaultRoleId) {
        public Policy {
            channels = Set.copyOf(channels);
        }
    }
}
