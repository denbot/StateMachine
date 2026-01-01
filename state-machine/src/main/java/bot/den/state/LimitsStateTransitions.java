package bot.den.state;

import bot.den.state.exceptions.InvalidStateTransition;

public interface LimitsStateTransitions<T> extends AttemptsTransitions<T> {
    boolean canTransitionState(T newState);
}
