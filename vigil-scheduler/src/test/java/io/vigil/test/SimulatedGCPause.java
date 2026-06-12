package io.vigil.test;

import java.time.Duration;

public final class SimulatedGCPause {

    private SimulatedGCPause() {}

    public static void pause(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
