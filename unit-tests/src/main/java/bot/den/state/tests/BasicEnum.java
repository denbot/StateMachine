package bot.den.state.tests;

import bot.den.state.HasStateTransitions;
import bot.den.state.StateMachine;

import java.util.Set;

@StateMachine
public enum BasicEnum implements HasStateTransitions<BasicEnum> {
    START,
    STATE_A,
    STATE_B,
    STATE_C,
    END;

    @Override
    public Set<BasicEnum> validTransitions(BasicEnum from) {
        return Set.of();
    }
}
