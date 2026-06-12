package io.vigil.core.exception;

public class VigilConfigurationException extends RuntimeException {

    public VigilConfigurationException(String message) {
        super(message);
    }

    public VigilConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
