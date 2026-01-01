package bot.den.state;

/**
 * Interface for types that restrict transitions between different implementations of a shared interface.
 * <p>
 * Used when a record field is an interface type and implementations need to control
 * which other implementations they can transition to.
 *
 * @param <T> the type being transitioned
 */
public interface LimitsTypeTransitions<T> extends AttemptsTransitions<T> {
    /**
     * Determines if transitioning to a different type is allowed.
     *
     * @param other the object to transition to (may be a different type)
     * @return true if the transition is valid, false otherwise
     */
    boolean canTransitionType(Object other);
}
