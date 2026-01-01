package bot.den.state.tests.implement;

import bot.den.state.LimitsStateTransitions;
import bot.den.state.LimitsTypeTransitions;

public enum Flavors implements MyInterface, LimitsTypeTransitions<Flavors>, LimitsStateTransitions<Flavors> {
    Vanilla, Chocolate, Strawberry;

    @Override
    public boolean canTransitionType(Object other) {
        return true;
    }

    @Override
    public boolean canTransitionState(Flavors newState) {
        return false;
    }
}
