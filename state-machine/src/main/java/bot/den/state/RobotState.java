package bot.den.state;

/**
 * Represents the operational state of an FRC robot.
 * <p>
 * This enum is automatically managed by the generated state machine code
 * and transitions based on the driver station control word.
 */
public enum RobotState {
    /** Robot is disabled */
    DISABLED,
    /** Robot is in autonomous enabled mode */
    AUTO,
    /** Robot is in teleoperated enabled mode */
    TELEOP,
    /** Robot is in test mode */
    TEST;
}
