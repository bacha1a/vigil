package io.vigil.scheduler;

import java.util.function.Consumer;

public final class VigilJobDefinition {

    private final String            name;
    private final String            cron;
    private final long              fixedRateMs;
    private final String            zone;
    private final long              lockTtlSeconds;
    private final boolean           warnOnSkip;
    private final Consumer<JobContext> action;

    private VigilJobDefinition(Builder builder) {
        this.name           = builder.name;
        this.cron           = builder.cron;
        this.fixedRateMs    = builder.fixedRateMs;
        this.zone           = builder.zone;
        this.lockTtlSeconds = builder.lockTtlSeconds;
        this.warnOnSkip     = builder.warnOnSkip;
        this.action         = builder.action;
    }

    public static Builder define(String name) {
        return new Builder(name);
    }

    public String name()           { return name; }
    public String cron()           { return cron; }
    public long fixedRateMs()      { return fixedRateMs; }
    public String zone()           { return zone; }
    public long lockTtlSeconds()   { return lockTtlSeconds; }
    public boolean warnOnSkip()    { return warnOnSkip; }
    public Consumer<JobContext> action() { return action; }

    public static final class Builder {

        private final String name;
        private String  cron          = "";
        private long    fixedRateMs   = -1;
        private String  zone          = "UTC";
        private long    lockTtlSeconds = 300;
        private boolean warnOnSkip    = true;
        private Consumer<JobContext> action;

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("VigilJobDefinition name must not be blank");
            }
            this.name = name;
        }

        public Builder cron(String expression) {
            this.cron = expression;
            return this;
        }

        public Builder fixedRateMs(long ms) {
            this.fixedRateMs = ms;
            return this;
        }

        public Builder zone(String zone) {
            this.zone = zone;
            return this;
        }

        public Builder lockTtlSeconds(long seconds) {
            this.lockTtlSeconds = seconds;
            return this;
        }

        public Builder warnOnSkip(boolean warn) {
            this.warnOnSkip = warn;
            return this;
        }

        public VigilJobDefinition run(Consumer<JobContext> action) {
            this.action = action;
            return new VigilJobDefinition(this);
        }

        public VigilJobDefinition run(Runnable action) {
            this.action = ctx -> action.run();
            return new VigilJobDefinition(this);
        }
    }
}
