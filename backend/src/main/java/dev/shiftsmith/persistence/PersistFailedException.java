package dev.shiftsmith.persistence;

/**
 * Thrown when persisting the problem document to the database fails. Callers
 * surface this as a non-2xx response (a {@code 503}) so a failed write is never
 * mistaken for a durable edit — the in-memory state is left untouched and the
 * client can safely retry.
 */
public class PersistFailedException extends RuntimeException {
    public PersistFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
