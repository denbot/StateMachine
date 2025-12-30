package bot.den.state;

public interface CanTransitionState<T> {
    boolean canTransitionTo(T newState);
}
