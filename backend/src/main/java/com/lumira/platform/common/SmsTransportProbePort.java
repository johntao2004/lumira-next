package com.lumira.platform.common;

/** Bounded, fixed synthetic loopback transport probe. It accepts no caller-supplied message. */
public interface SmsTransportProbePort {
    String sendLocalTest();
}
