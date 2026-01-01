package bot.den.state.tests;

/**
 * This enum does not implement any state transition limitations, so the RobotRecord will have two fields that do not
 * implement any limitations. The resulting record data class should also not generate any methods to limit state
 * transitions.
 */
public enum TwoStateEnum {
    A,
    B;
}
