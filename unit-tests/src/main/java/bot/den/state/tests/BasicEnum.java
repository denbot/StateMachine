package bot.den.state.tests;

import bot.den.state.LimitsStateTransitions;
import bot.den.state.StateMachine;

import java.util.Set;

@StateMachine
public enum BasicEnum implements LimitsStateTransitions<BasicEnum> {
    START,
    STATE_A,
    STATE_B,
    STATE_C,
    STATE_D,
    END;

    @Override
    public boolean canTransitionState(BasicEnum newState) {
        return (switch (this) {
            case START -> Set.of(STATE_A);
            case STATE_A -> Set.of(STATE_B, STATE_C);
            case STATE_B, STATE_C -> Set.of(STATE_D);
            case STATE_D -> Set.of(END);
            case END -> Set.of();
        }).contains(newState);
    }
}
