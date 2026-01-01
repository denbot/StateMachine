package bot.den.state.exceptions;

/**
 * Thrown when a state transition is not allowed.
 * <p>
 * This can occur when:
 * <ul>
 *   <li>An implementation of {@link bot.den.state.LimitsStateTransitions#canTransitionState} returns false</li>
 *   <li>An implementation of {@link bot.den.state.LimitsTypeTransitions#canTransitionType} returns false</li>
 *   <li>A transition is marked with {@code failLoudly()}</li>
 * </ul>
 */
public class InvalidStateTransition extends RuntimeException {
    /**
     * Creates an exception for an invalid state transition.
     *
     * @param fromState the current state
     * @param toState the attempted target state
     */
    public InvalidStateTransition(Object fromState, Object toState) {
        super("Cannot transition from " + fromState + " to " + toState);
    }

    /**
     * Creates an exception for an invalid state transition with a cause.
     *
     * @param fromState the current state
     * @param toState the attempted target state
     * @param throwable the cause of the failure
     */
    public InvalidStateTransition(Object fromState, Object toState, Throwable throwable) {
        super("Cannot transition from " + fromState + " to " + toState, throwable);
    }
}
