package bot.den.state;

public enum RobotState implements CanTransitionState<RobotState> {
    DISABLED,
    AUTO,
    TELEOP,
    TEST;

    @Override
    public boolean canTransitionTo(RobotState newState) {
        // The robot can be transitioned to any state. This enum is not what should control what mode the robot is in.
        return true;
    }
}
