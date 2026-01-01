package bot.den.state;

public interface LimitsTypeTransitions<T> extends AttemptsTransitions<T> {
    boolean canTransitionType(Object other);
}
