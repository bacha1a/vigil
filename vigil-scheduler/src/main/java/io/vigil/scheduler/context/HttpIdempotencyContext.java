package io.vigil.scheduler.context;

import io.vigil.core.model.IdempotencyKey;

public final class HttpIdempotencyContext {

    private static final ThreadLocal<IdempotencyKey> CURRENT = new ThreadLocal<>();

    private HttpIdempotencyContext() {}

    public static void bind(IdempotencyKey key) { CURRENT.set(key); }
    public static void unbind()                 { CURRENT.remove(); }
    public static IdempotencyKey current()      { return CURRENT.get(); }
}
