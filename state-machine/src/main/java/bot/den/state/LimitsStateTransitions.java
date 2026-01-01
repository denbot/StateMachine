package bot.den.state;

/**
 * Interface for types that restrict transitions to specific states of the same type.
 * <p>
 * Typically implemented by enums to define valid state transitions.
 *
 * @param <T> the type being transitioned
 */
public interface LimitsStateTransitions<T> extends AttemptsTransitions<T> {
    /**
     * Determines if transitioning to the specified state is allowed.
     *
     * @param newState the state to transition to
     * @return true if the transition is valid, false otherwise
     */
    boolean canTransitionState(T newState);
}
