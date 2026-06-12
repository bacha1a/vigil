package io.vigil.core.support;

import io.vigil.core.exception.CheckpointTypeException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CheckpointTypeValidator {

    private static final Set<Class<?>> SCALAR_TYPES = Set.of(
            Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class, Character.class,
            String.class, UUID.class,
            LocalDate.class, LocalDateTime.class, Instant.class);

    private CheckpointTypeValidator() {}

    public static void validate(Object value) {
        if (value == null) return;
        validateType(value.getClass(), "$root", new HashSet<>());
    }

    public static void validateClass(Class<?> type) {
        validateType(type, "$root", new HashSet<>());
    }

    private static void validateType(Class<?> type, String path, Set<Class<?>> seen) {
        if (type == null)                  return;
        if (type.isPrimitive())            return;
        if (SCALAR_TYPES.contains(type))   return;
        if (type.isEnum())                 return;
        if (type.isRecord()) {
            if (!seen.add(type)) return;
            for (var component : type.getRecordComponents()) {
                validateType(component.getType(), path + "." + component.getName(), seen);
            }
            return;
        }

        String reason;
        if (Collection.class.isAssignableFrom(type)) {
            reason = "Collections are not allowed. Store a cursor or ID and "
                   + "re-fetch data. For large datasets use ctx.forEachPage().";
        } else if (Map.class.isAssignableFrom(type)) {
            reason = "Maps are not allowed. Use a record with named fields.";
        } else if (type.isArray()) {
            reason = "Arrays are not allowed. Use a record with named fields.";
        } else {
            reason = "Only Java records are allowed as compound checkpoint values. '"
                   + type.getSimpleName() + "' is a class, not a record. "
                   + "Convert it to a record containing only primitives, "
                   + "String, UUID, LocalDate/DateTime/Instant, Enum, or records.";
        }
        throw new CheckpointTypeException(
                "Invalid checkpoint type at '" + path + "': " + type.getName() + ". " + reason);
    }
}
