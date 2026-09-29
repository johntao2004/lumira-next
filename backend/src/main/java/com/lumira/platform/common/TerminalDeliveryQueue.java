package com.lumira.platform.common;

import java.time.Duration;

/** Kernel port used by retention scheduling; concrete mail/SMS modules stay out of common code. */
public interface TerminalDeliveryQueue {
    int purgeTerminal(Duration retention, int batchSize);
}
