/**
 * 逐项尝试所有资源释放后再传播首个异常并附带后续异常，避免单步清理失败截断其余释放动作。 Attempts every resource release before propagating
 * the first failure with subsequent failures suppressed, preventing one cleanup error from skipping
 * the remaining actions.
 */
package com.lumira.platform.plugins;

/**
 * 尽力完成全部清理，再按确定顺序传播失败。 Best-effort teardown with deterministic failure propagation after all
 * releases are attempted.
 */
final class PluginCleanup {
    private PluginCleanup() {}

    static void closeAll(Iterable<? extends AutoCloseable> actions) throws Exception {
        Throwable failure = null;
        for (var action : actions) {
            try {
                action.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }
}
