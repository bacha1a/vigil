package io.vigil.scheduler.context;

public final class ExactlyOnceContext {

    private static final ThreadLocal<ExactlyOnceContext> CURRENT = new ThreadLocal<>();

    private final String runId;
    private final long   fencingToken;
    private final String itemId;

    private ExactlyOnceContext(String runId, long fencingToken, String itemId) {
        this.runId        = runId;
        this.fencingToken = fencingToken;
        this.itemId       = itemId;
    }

    public static void bind(long fencingToken, String itemId) {
        bind(null, fencingToken, itemId);
    }

    public static void bind(String runId, long fencingToken, String itemId) {
        CURRENT.set(new ExactlyOnceContext(runId, fencingToken, itemId));
    }

    public static void unbind()                { CURRENT.remove(); }
    public static ExactlyOnceContext current() { return CURRENT.get(); }
    public String runId()                      { return runId; }
    public long   fencingToken()               { return fencingToken; }
    public String itemId()                     { return itemId; }

    public String stableId() {
        return runId != null ? runId : Long.toString(fencingToken);
    }
}
