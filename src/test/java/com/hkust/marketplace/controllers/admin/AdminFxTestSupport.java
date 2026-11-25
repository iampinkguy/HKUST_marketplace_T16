package com.hkust.marketplace.controllers.admin;

import javafx.application.Platform;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertTrue;

/** Shares one toolkit across desktop test classes; the test runner owns the JVM lifetime. */
public final class AdminFxTestSupport {
    private static boolean started;
    private AdminFxTestSupport() {}

    public static synchronized void startToolkit() throws Exception {
        if (started) return;
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        started = true;
    }
}
