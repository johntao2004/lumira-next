package com.lumira.platform.identity;

import java.util.concurrent.Semaphore;

/** One process-wide bound for expensive Argon2 work on anonymous credential flows. */
public final class PasswordHashCapacity {
    private static final Semaphore SLOTS = new Semaphore(2);

    private PasswordHashCapacity() {}

    public static boolean tryAcquire() {
        return SLOTS.tryAcquire();
    }

    public static void release() {
        SLOTS.release();
    }
}
