package bot.den.state.tests;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BasicEnumStateMachineTest {
    @BeforeEach
    public void setup() {
        assertTrue(HAL.initialize(500, 0));
    }

    @Test
    void initialState() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        assertEquals(BasicEnum.START, machine.currentState());
    }

    @Test
    void whenClause() {
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

    @Test
    void always() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        // Set up our state machine transition
        machine.from(BasicEnum.START).to(BasicEnum.STATE_B).always();

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transitions happen
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_B, machine.currentState());
    }

    @Test
    void commandInitialize() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up our state machine transition. Always run to trigger the move to a Command
        machine
                .from(BasicEnum.START)
                .to(BasicEnum.STATE_B)
                .always()
                .run(
                        Commands.runOnce(() -> test.set(true)).ignoringDisable(true)
                );

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transitions happen
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_B, machine.currentState());

        // The runOnce command runs in the initializer and therefore runs upon being scheduled
        assertTrue(test.get());
    }

    @Test
    void commandRun() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up our state machine transition. Always run to trigger the move to a Command
        machine
                .from(BasicEnum.START)
                .to(BasicEnum.STATE_B)
                .always()
                .run(
                        Commands.run(() -> test.set(true)).ignoringDisable(true)
                );

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transitions happen
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_B, machine.currentState());

        // The run command runs in the execute loop and therefore needs the scheduler
        assertFalse(test.get());

        CommandScheduler.getInstance().run();

        // Now it should be true
        assertTrue(test.get());
    }
}
