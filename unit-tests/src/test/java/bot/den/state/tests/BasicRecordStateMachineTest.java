package bot.den.state.tests;

import bot.den.state.exceptions.InvalidStateTransition;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BasicRecordStateMachineTest {
    private BasicRecordStateMachine machine;

    @BeforeEach
    public void setup() {
        assertTrue(HAL.initialize(500, 0));

        this.machine = new BasicRecordStateMachine(
                TwoStateEnum.A,
                BasicEnum.START,
                BasicRecord.InnerEnum.STAR
        );
    }

    @AfterEach
    public void cleanup() {
        // This method runs after each test to reset the scheduler state
        CommandScheduler.getInstance().cancelAll();
        CommandScheduler.getInstance().run(); // Call run() to execute end() methods
    }


    @Test
    void canCreateStateMachine() {
        // Technically we create the machine in `setup`, but we do want to verify the state here

        var state = this.machine.currentState();
        assertEquals(TwoStateEnum.A, state.twoState());
        assertEquals(BasicEnum.START, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());
    }

    @Test
    void canTransitionGivenPartialSpecifiers() {
        this.machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        // Verify nothing changed
        var state = this.machine.currentState();
        assertEquals(TwoStateEnum.A, state.twoState());
        assertEquals(BasicEnum.START, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());

        // Start the transitions
        this.machine.poll();

        // Verify the new state
        state = this.machine.currentState();
        assertEquals(TwoStateEnum.A, state.twoState());
        assertEquals(BasicEnum.STATE_A, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());
    }

    @Test
    void canTransitionDifferentComponentsUsingPartialSpecifiers() {
        this.machine.state(BasicEnum.START).to(TwoStateEnum.B).transitionAlways();

        // Verify nothing changed
        var state = this.machine.currentState();
        assertEquals(TwoStateEnum.A, state.twoState());
        assertEquals(BasicEnum.START, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());

        // Start the transitions
        this.machine.poll();

        // Verify the new state
        state = this.machine.currentState();
        assertEquals(TwoStateEnum.B, state.twoState());
        assertEquals(BasicEnum.START, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());
    }

    @Test
    void canLimitTransitionUsingMultipleSpecifiers() {
        // This shouldn't transition until the state has both of the required states
        this.machine
                .state(TwoStateEnum.B, BasicEnum.STATE_A)
                .to(BasicRecord.InnerEnum.CIRCLE)
                .transitionAlways();

        // No transition should occur here
        this.machine.poll();

        // Verify nothing changed
        var state = this.machine.currentState();
        assertEquals(TwoStateEnum.A, state.twoState());
        assertEquals(BasicEnum.START, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());

        // Force a transition for one part of the specifier
        var twoStateCommand = this.machine.transitionTo(TwoStateEnum.B);
        CommandScheduler.getInstance().schedule(twoStateCommand);

        // Verify our partial state change
        state = this.machine.currentState();
        assertEquals(TwoStateEnum.B, state.twoState());
        assertEquals(BasicEnum.START, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());

        // Force a transition for the other part of the specifier
        var basicCommand = this.machine.transitionTo(BasicEnum.STATE_A);
        CommandScheduler.getInstance().schedule(basicCommand);

        // Verify our partial state change
        state = this.machine.currentState();
        assertEquals(TwoStateEnum.B, state.twoState());
        assertEquals(BasicEnum.STATE_A, state.basic());
        assertEquals(BasicRecord.InnerEnum.STAR, state.inner());

        // Poll should now transition us to the final change
        this.machine.poll();

        // Verify the final change
        state = this.machine.currentState();
        assertEquals(TwoStateEnum.B, state.twoState());
        assertEquals(BasicEnum.STATE_A, state.basic());
        assertEquals(BasicRecord.InnerEnum.CIRCLE, state.inner());
    }

    @Test
    void triggerWorksOnSubsetOfData() {
        var trigger = this.machine
                .state(BasicEnum.STATE_A, BasicRecord.InnerEnum.CIRCLE)
                .trigger();

        // Make sure it isn't already triggered
        assertFalse(trigger.getAsBoolean());

        var firstCommand = this.machine.transitionTo(TwoStateEnum.B, BasicEnum.STATE_A);
        CommandScheduler.getInstance().schedule(firstCommand);

        // Trigger shouldn't be active yet even though it partially matches
        assertFalse(trigger.getAsBoolean());

        var secondCommand = this.machine.transitionTo(BasicRecord.InnerEnum.CIRCLE);
        CommandScheduler.getInstance().schedule(secondCommand);

        // Finally, this should be true
        assertTrue(trigger.getAsBoolean());
    }

    @Test
    void whenWorksOnSubsetOfData() {
        final AtomicBoolean test = new AtomicBoolean(false);

        this.machine
                .state(BasicEnum.STATE_A)
                .to(BasicEnum.STATE_B)
                .transitionWhen(test::get);

        // Nothing should happen as our boolean isn't set AND we're not in the correct starting state
        this.machine.poll();
        assertEquals(BasicEnum.START, this.machine.currentState().basic());

        // Now let's try setting our trigger
        test.set(true);

        // Nothing should happen still as we aren't in the right starting spot
        this.machine.poll();
        assertEquals(BasicEnum.START, this.machine.currentState().basic());

        // Reset our flag and move the state over manually
        test.set(false);
        var command = this.machine.transitionTo(BasicEnum.STATE_A);
        CommandScheduler.getInstance().schedule(command);

        // Verify we've only moved to A, even after polling
        assertEquals(BasicEnum.STATE_A, this.machine.currentState().basic());
        this.machine.poll();
        assertEquals(BasicEnum.STATE_A, this.machine.currentState().basic());

        // One more time, let's set our trigger
        test.set(true);
        assertEquals(BasicEnum.STATE_A, this.machine.currentState().basic());

        // Polling should move it over
        this.machine.poll();
        assertEquals(BasicEnum.STATE_B, this.machine.currentState().basic());
    }

    @Test
    void addingANewSpecifierTransitionAlways() {
        this.machine
                .state(BasicEnum.STATE_A)
                .to(BasicEnum.STATE_B)
                .transitionAlways();

        // Nothing should happen yet
        this.machine.poll();
        assertEquals(BasicEnum.START, this.machine.currentState().basic());

        // Now we set up our transition from START -> STATE_A
        this.machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .transitionAlways();

        // This should transition once to A
        this.machine.poll();
        assertEquals(BasicEnum.STATE_A, this.machine.currentState().basic());

        // This should transition once more to B
        this.machine.poll();
        assertEquals(BasicEnum.STATE_B, this.machine.currentState().basic());
    }

    @Test
    void failLoudlyOnPartialRecordTransition() {
        // Set up failLoudly on a partial state match
        this.machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .failLoudly();

        // Force the transition and expect it to fail
        assertThrows(InvalidStateTransition.class,
                () -> CommandScheduler.getInstance().schedule(this.machine.transitionTo(BasicEnum.STATE_A)));
    }

    @Test
    void failLoudlyWithRunCommand() {
        final AtomicBoolean commandRan = new AtomicBoolean(false);

        // Set up transition with run command
        this.machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .transitionAlways()
                .run(Commands.runOnce(() -> commandRan.set(true)).ignoringDisable(true));

        // Set up failLoudly on the same transition
        this.machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .failLoudly();

        // Transition should fail
        assertThrows(InvalidStateTransition.class, this.machine::poll);

        // Command should not run when failLoudly prevents the transition
        assertFalse(commandRan.get());
    }
}
