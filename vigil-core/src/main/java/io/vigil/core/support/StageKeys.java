package io.vigil.core.support;

public final class StageKeys {

    private StageKeys() {}

    public static String toDbKey(Enum<?> stage) {
        return stage.getDeclaringClass().getSimpleName() + "." + stage.name();
    }
}
