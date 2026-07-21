package io.vigil.scheduler.heartbeat;

import java.time.Duration;

public final class HeartbeatEntry {

    private final String   jobName;
    private final long     fencingToken;
    private final Thread   jobThread;
    private final Duration ttl;
    private volatile long  lastRenewedAtMillis;

    public HeartbeatEntry(String jobName, long fencingToken, Thread jobThread, Duration ttl) {
        this.jobName             = jobName;
        this.fencingToken        = fencingToken;
        this.jobThread           = jobThread;
        this.ttl                 = ttl;
        this.lastRenewedAtMillis = System.currentTimeMillis();
    }

    public String   jobName()      { return jobName; }
    public long     fencingToken() { return fencingToken; }
    public Thread   jobThread()    { return jobThread; }
    public Duration ttl()          { return ttl; }

    public long lastRenewedAtMillis() {
        return lastRenewedAtMillis;
    }

    public void markRenewed() {
        this.lastRenewedAtMillis = System.currentTimeMillis();
    }
}
