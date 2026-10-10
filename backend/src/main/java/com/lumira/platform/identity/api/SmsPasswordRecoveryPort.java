package com.lumira.platform.identity.api;

/** Identity-owned capability gate for enabling SMS registration. */
public interface SmsPasswordRecoveryPort {
    boolean supported();
}
