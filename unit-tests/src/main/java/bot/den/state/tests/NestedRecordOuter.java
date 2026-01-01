package bot.den.state.tests;

import bot.den.state.RobotState;
import bot.den.state.StateMachine;

@StateMachine
public record NestedRecordOuter(
        RobotState robotState,
        NestedRecord nested
) {
    record NestedRecord(TwoStateEnum twoStateEnum) {

    }
}
