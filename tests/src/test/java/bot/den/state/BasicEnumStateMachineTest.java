package bot.den.state;

import bot.den.state.tests.BasicEnum;
import bot.den.state.tests.BasicEnumStateMachine;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BasicEnumStateMachineTest {
    @Test
    void testInitialState() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        assertEquals(BasicEnum.START, machine.currentState());
    }

    @Test
    void testWhenClause() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up our state machine transition
        machine.from(BasicEnum.START).to(BasicEnum.STATE_B).transitionWhen(test::get);

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if any transitions happen
        machine.poll();

        // Ensure no transition happened
        assertEquals(BasicEnum.START, machine.currentState());

        // Update our test to allow the transition to pass
        test.set(true);

        // Check state again (to ensure `currentState` is just returning state and not checking anything)
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transition did happen this time
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_B, machine.currentState());
    }
}
