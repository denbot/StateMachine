package bot.den.state.tests.implement;

import bot.den.state.exceptions.InvalidStateTransition;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class MyRecordTest {
    @BeforeEach
    public void setup() {
        assertTrue(HAL.initialize(500, 0));
    }

    @AfterEach
    public void cleanup() {
        CommandScheduler.getInstance().cancelAll();
        CommandScheduler.getInstance().run();
    }

    @Test
    void canTransitionTypeIsCalled() {
        var machine = new MyRecordStateMachine(Shapes.SQUARE);

        // This is disallowed in our canTransitionType method on the Shapes class, so we don't even have to wait for the
        // state to transition. Merely trying to set up that transition will fail.
        assertThrows(InvalidStateTransition.class, () -> machine.state(Shapes.SQUARE).to(Flavors.Strawberry));
    }

    @Test
    void canTransitionBetweenDifferentImplementations() {
        var machine = new MyRecordStateMachine(Shapes.SQUARE);

        // Force a transition to a different interface implementation
        var command = machine.transitionTo(Flavors.Chocolate);
        CommandScheduler.getInstance().schedule(command);

        // Verify we transitioned from Shapes to Flavors
        assertEquals(Flavors.Chocolate, machine.currentState().field());
    }

    @Test
    void canTransitionWithinSameType() {
        var machine = new MyRecordStateMachine(Shapes.SQUARE);

        // Transition between two values of the same type
        var command = machine.transitionTo(Shapes.CIRCLE);
        CommandScheduler.getInstance().schedule(command);

        assertEquals(Shapes.CIRCLE, machine.currentState().field());
    }

    @Test
    void transitionWhenWorksWithInterfaces() {
        var machine = new MyRecordStateMachine(Shapes.SQUARE);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up conditional transition
        machine
                .state(Shapes.SQUARE)
                .to(Shapes.CIRCLE)
                .transitionWhen(test::get);

        // Verify no transition yet
        machine.poll();
        assertEquals(Shapes.SQUARE, machine.currentState().field());

        // Enable the transition
        test.set(true);
        machine.poll();

        // Verify transition occurred
        assertEquals(Shapes.CIRCLE, machine.currentState().field());
    }

    @Test
    void triggerWorksWithInterfaces() {
        var machine = new MyRecordStateMachine(Shapes.SQUARE);

        // Create trigger for a different implementation type
        var trigger = machine.state(Flavors.Vanilla).trigger();

        assertFalse(trigger.getAsBoolean());

        // Transition to the target state
        var command = machine.transitionTo(Flavors.Vanilla);
        CommandScheduler.getInstance().schedule(command);

        assertTrue(trigger.getAsBoolean());
    }

    @Test
    void runCommandWorksWithInterfaceTransitions() {
        var machine = new MyRecordStateMachine(Shapes.SQUARE);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up transition with command
        machine
                .state(Shapes.SQUARE)
                .to(Shapes.CIRCLE)
                .transitionAlways()
                .run(Commands.runOnce(() -> test.set(true)).ignoringDisable(true));

        machine.poll();

        // Command should have run on transition
        assertTrue(test.get());
        assertEquals(Shapes.CIRCLE, machine.currentState().field());
    }
}
