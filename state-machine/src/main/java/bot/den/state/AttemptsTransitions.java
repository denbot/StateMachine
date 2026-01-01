package bot.den.state;

import bot.den.state.exceptions.InvalidStateTransition;

public interface AttemptsTransitions<T> {
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
