package com.lumira.platform.identity;

/** Separate challenge namespaces prevent reuse across registration, login and phone recovery. */
enum PublicAuthPurpose {
    REGISTRATION,
    LOGIN,
    SMS_RECOVERY,
    REAUTHENTICATION
}
