package bot.den.state;

import bot.den.state.exceptions.InvalidStateTransition;

/**
 * Base interface for types that validate state transitions.
 * <p>
 * Provides a default implementation that checks both state and type transition limits
 * if the implementing type also implements {@link LimitsStateTransitions} or {@link LimitsTypeTransitions}.
 *
 * @param <T> the type being transitioned
 */
public interface AttemptsTransitions<T> {
    /**
     * Attempts to transition to a new state, validating the transition if limits are defined.
     *
     * @param newState the state to transition to
     * @throws InvalidStateTransition if the transition is not allowed
     */
    default void attemptTransitionTo(T newState) throws InvalidStateTransition {
        if(this instanceof LimitsStateTransitions<T> lst) {
            if (!lst.canTransitionState(newState)) {
                throw new InvalidStateTransition(this, newState);
            }
        }

        if(this instanceof LimitsTypeTransitions<T> ltt) {
            if (!ltt.canTransitionType(newState)) {
                throw new InvalidStateTransition(this, newState);
            }
        }
    }
}
