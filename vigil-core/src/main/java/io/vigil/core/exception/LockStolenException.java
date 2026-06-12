package io.vigil.core.exception;

public class LockStolenException extends RuntimeException {

    public LockStolenException(String message) {
        super(message);
    }

    public LockStolenException(String message, Throwable cause) {
        super(message, cause);
    }
}
