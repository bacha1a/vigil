package io.vigil.core.exception;

public class CheckpointTypeException extends RuntimeException {

    public CheckpointTypeException(String message) {
        super(message);
    }

    public CheckpointTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
