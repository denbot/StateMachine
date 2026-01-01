package bot.den.state;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an enum or record for state machine code generation.
 * <p>
 * Enums can implement {@link LimitsStateTransitions} to restrict valid state transitions.
 * Records represent composite states where each field can be independently transitioned.
 * Record fields can be enums, interfaces, or other records.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface StateMachine {
}
