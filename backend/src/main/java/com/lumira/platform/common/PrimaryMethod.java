package com.lumira.platform.common;

/** Primary authentication methods accepted by the common authentication pipeline. */
public enum PrimaryMethod {
    PASSWORD,
    EMAIL_OTP,
    SMS_OTP,
    PASSKEY,
    WECHAT
}
