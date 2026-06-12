package io.vigil.core.model;

public record IdempotencyKey(String value) {

    public static IdempotencyKey of(long fencingToken, String itemId) {
        return of(Long.toString(fencingToken), itemId);
    }

    public static IdempotencyKey forMethod(long fencingToken, String itemId, String methodName) {
        return forMethod(Long.toString(fencingToken), itemId, methodName);
    }

    public static IdempotencyKey of(String stableId, String itemId) {
        return new IdempotencyKey("vigil_" + stableId + "_" + itemId);
    }

    public static IdempotencyKey forMethod(String stableId, String itemId, String methodName) {
        return new IdempotencyKey("vigil_" + stableId + "_" + itemId + "_" + methodName);
    }
}
