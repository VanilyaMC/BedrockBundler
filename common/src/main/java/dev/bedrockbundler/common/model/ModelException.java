package dev.bedrockbundler.common.model;

/** A model cannot be converted; the converter skips it with a warning. */
final class ModelException extends RuntimeException {

    ModelException(String message) {
        super(message);
    }

    ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
