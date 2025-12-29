package bot.den.state;

import java.util.Set;

public interface HasStateTransitions<T> {
    Set<T> validTransitions(T from);
}
