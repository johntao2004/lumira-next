package com.lumira.platform.common;

/**
 * 通用认证流程接受的主认证方式。 Primary authentication methods accepted by the common authentication pipeline.
 */
public enum PrimaryMethod {
    PASSWORD,
    EMAIL_OTP,
    SMS_OTP,
    PASSKEY,
    WECHAT
}
