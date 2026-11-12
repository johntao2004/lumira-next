package com.lumira.platform.common;

/** A plugin transaction lost admission; callers must roll back the whole transaction. */
public final class PluginWorkFencedException extends java.sql.SQLTransientException {
    public PluginWorkFencedException() {
        super("pluginTransactionFenced", "40001");
    }

    public PluginWorkFencedException(java.sql.SQLException cause) {
        super("pluginTransactionFenced", "40001", cause);
    }
}
