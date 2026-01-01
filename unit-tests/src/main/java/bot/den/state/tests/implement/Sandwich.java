package bot.den.state.tests.implement;

import bot.den.state.LimitsStateTransitions;
import bot.den.state.LimitsTypeTransitions;

public record Sandwich(boolean isASandwich) implements MyInterface, LimitsTypeTransitions<Sandwich>, LimitsStateTransitions<Sandwich> {
    @Override
    public boolean canTransitionType(Object other) {
        if(other instanceof Flavors) {
            return false;
        }
        return true;
    }

    @Override
    public boolean canTransitionState(Sandwich newState) {
        return newState.isASandwich;
    }
}
