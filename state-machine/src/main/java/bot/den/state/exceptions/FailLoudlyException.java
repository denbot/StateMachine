package bot.den.state.exceptions;

/**
 * Thrown when a transition marked with {@code failLoudly()} is attempted.
 * <p>
 * This exception is used to immediately halt execution when a dangerous or
 * unexpected state transition is attempted.
 */
public class FailLoudlyException extends RuntimeException {
    /**
     * Creates an exception for a transition that should not be allowed
     *
     * @param message description of why the transition should fail loudly
     */
    public FailLoudlyException(String message) {
        super(message);
    }
}
