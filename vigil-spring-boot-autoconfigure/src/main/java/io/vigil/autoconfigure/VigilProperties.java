package io.vigil.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.vigil")
public class VigilProperties {

    public enum Backend { JDBC, REDIS, MONGO, DYNAMODB, AUTO }

    private boolean enabled               = true;
    private Backend backend               = Backend.AUTO;
    private String  podId                 = null;
    private int     checkpointSizeLimitKb = 10;
    private long    orphanScanIntervalMs  = 30_000L;
    private int     lockTtlSeconds               = 300;
    private int     runHistoryRetention          = 100;
    private long    runHistoryCleanupIntervalMs  = 3_600_000L;

    public boolean isEnabled()                          { return enabled; }
    public void setEnabled(boolean enabled)             { this.enabled = enabled; }

    public Backend getBackend()                         { return backend; }
    public void setBackend(Backend backend)             { this.backend = backend; }

    public String getPodId()                            { return podId; }
    public void setPodId(String podId)                  { this.podId = podId; }

    public int getCheckpointSizeLimitKb()               { return checkpointSizeLimitKb; }
    public void setCheckpointSizeLimitKb(int v)         { this.checkpointSizeLimitKb = v; }

    public long getOrphanScanIntervalMs()               { return orphanScanIntervalMs; }
    public void setOrphanScanIntervalMs(long v)         { this.orphanScanIntervalMs = v; }

    public int getLockTtlSeconds()                      { return lockTtlSeconds; }
    public void setLockTtlSeconds(int lockTtlSeconds)   { this.lockTtlSeconds = lockTtlSeconds; }

    public int getRunHistoryRetention()                        { return runHistoryRetention; }
    public void setRunHistoryRetention(int v)                  { this.runHistoryRetention = v; }

    public long getRunHistoryCleanupIntervalMs()               { return runHistoryCleanupIntervalMs; }
    public void setRunHistoryCleanupIntervalMs(long v)         { this.runHistoryCleanupIntervalMs = v; }
}
