package bot.den.state.tests;

import bot.den.state.CanTransitionState;

import java.util.Set;

public enum TwoStateEnum implements CanTransitionState<TwoStateEnum> {
    A,
    B;

    @Override
    public boolean canTransitionTo(TwoStateEnum newState) {
        return (switch (this) {
            case A -> Set.of(B);
            case B -> Set.of(A);
        }).contains(newState);
    }
}
