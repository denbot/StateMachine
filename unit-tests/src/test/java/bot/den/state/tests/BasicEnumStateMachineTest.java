package bot.den.state.tests;

import bot.den.state.exceptions.InvalidStateTransition;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj.event.EventLoop;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BasicEnumStateMachineTest {
    @BeforeEach
    public void setup() {
        assertTrue(HAL.initialize(500, 0));
    }

    @AfterEach
    public void cleanup() {
        // This method runs after each test to reset the scheduler state
        CommandScheduler.getInstance().cancelAll();
        CommandScheduler.getInstance().run(); // Call run() to execute end() methods
    }

    @Test
    void initialState() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        assertEquals(BasicEnum.START, machine.currentState());
    }

    @Test
    void whenClause() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up our state machine transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionWhen(test::get);

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
        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void always() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Set up our state machine transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transitions happen
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void commandInitialize() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up our state machine transition. Always run to trigger the move to a Command
        machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .transitionAlways()
                .run(
                        Commands.runOnce(() -> test.set(true)).ignoringDisable(true)
                );

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transitions happen
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_A, machine.currentState());

        // The runOnce command runs in the initializer and therefore runs upon being scheduled
        assertTrue(test.get());
    }

    @Test
    void commandRun() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Set up our state machine transition. Always run to trigger the move to a Command
        machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .transitionAlways()
                .run(
                        Commands.run(() -> test.set(true)).ignoringDisable(true)
                );

        // Test our initial state
        assertEquals(BasicEnum.START, machine.currentState());

        // Poll to see if the transitions happen
        machine.poll();

        // Verify the transition
        assertEquals(BasicEnum.STATE_A, machine.currentState());

        // The run command runs in the execute loop and therefore needs the scheduler
        assertFalse(test.get());

        CommandScheduler.getInstance().run();

        // Now it should be true
        assertTrue(test.get());
    }

    @Test
    void invalidTransition() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // START -> END is invalid and should fail immediately
        assertThrows(
                InvalidStateTransition.class,
                () -> machine.state(BasicEnum.START).to(BasicEnum.END)
        );
    }

    @Test
    void oneTransitionAtATime() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Set up two transitions to always run
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();
        machine.state(BasicEnum.STATE_A).to(BasicEnum.STATE_B).transitionAlways();

        // Assert that we're at the start state
        assertEquals(BasicEnum.START, machine.currentState());

        // Move one state
        machine.poll();

        // Check the next state
        assertEquals(BasicEnum.STATE_A, machine.currentState());

        // Move one more state
        machine.poll();

        // Check the final state
        assertEquals(BasicEnum.STATE_B, machine.currentState());

        // No more state moves
        machine.poll();
        assertEquals(BasicEnum.STATE_B, machine.currentState());
    }

    @Test
    void basicTrigger() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Create our trigger
        Trigger trigger = machine.state(BasicEnum.STATE_A).trigger();

        // Ensure `poll` will force a state transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        assertFalse(trigger.getAsBoolean());

        machine.poll();

        assertTrue(trigger.getAsBoolean());
    }

    @Test
    void triggerDefaultEventLoop() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Create our trigger
        machine
                .state(BasicEnum.STATE_A)
                .trigger()
                .onTrue(
                        Commands.runOnce(() -> test.set(true)).ignoringDisable(true)
                );

        // Ensure `poll` will force a state transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        machine.poll();
        CommandScheduler.getInstance().run();

        assertTrue(test.get());
    }

    @Test
    void triggerCustomEventLoop() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Custom event loop
        EventLoop eventLoop = new EventLoop();

        // Create our trigger
        machine
                .state(BasicEnum.STATE_A)
                .trigger(eventLoop)
                .onTrue(
                        Commands.runOnce(() -> test.set(true)).ignoringDisable(true)
                );

        // Ensure `poll` will force a state transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        machine.poll();
        CommandScheduler.getInstance().run();

        // The default loop shouldn't have done anything
        assertFalse(test.get());

        // But our event loop poll will
        eventLoop.poll();

        assertTrue(test.get());
    }

    @Test
    void triggerOnlyRunsOnChange() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean test = new AtomicBoolean(false);

        // Create our trigger
        machine
                .state(BasicEnum.START)
                .trigger()
                .onTrue(
                        Commands.runOnce(() -> test.set(true)).ignoringDisable(true)
                );

        // There should be no state to transition, but we still need to make sure it doesn't affect the outcome
        machine.poll();
        CommandScheduler.getInstance().run();

        // The trigger did not run because START was the original state
        assertFalse(test.get());
    }

    @Test
    void transitionToCommand() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Create our command
        Command command = machine.transitionTo(BasicEnum.STATE_A);

        // Ensure creating the command didn't do anything
        assertEquals(BasicEnum.START, machine.currentState());

        // Scheduling a command calls the initialize method, which should be what does the state transition
        CommandScheduler.getInstance().schedule(command);

        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void transitionToCommandOnlyRunsOnce() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Create our command
        Command command = machine.transitionTo(BasicEnum.STATE_A);

        // Always jump from A to B on a machine.poll() call
        machine.state(BasicEnum.STATE_A).to(BasicEnum.STATE_B).transitionAlways();

        // Ensure creating the command didn't do anything
        assertEquals(BasicEnum.START, machine.currentState());

        // Scheduling a command calls the initialize method, which should be what does the state transition
        CommandScheduler.getInstance().schedule(command);

        // The command is still scheduled as the command scheduler hasn't run to clear that state yet
        assertTrue(command.isScheduled());

        // Verify we're still at the A state
        assertEquals(BasicEnum.STATE_A, machine.currentState());

        // When we poll, the state should jump to B due to the always call above
        machine.poll();
        assertEquals(BasicEnum.STATE_B, machine.currentState());

        // For extra assurance, we make sure running the scheduler didn't change the state
        CommandScheduler.getInstance().run();
        assertEquals(BasicEnum.STATE_B, machine.currentState());
    }

    @Test
    void runPollCommand() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Set up our state machine transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        CommandScheduler.getInstance().run();

        // Nothing should change yet
        assertEquals(BasicEnum.START, machine.currentState());

        CommandScheduler.getInstance().schedule(machine.runPollCommand());

        // We shouldn't do anything when the command is initialized
        assertEquals(BasicEnum.START, machine.currentState());

        // This should actually run the poll command
        CommandScheduler.getInstance().run();

        // Verify our final state
        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void invalidTransitionCannotBeForced() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);
        CommandScheduler.getInstance().schedule(machine.runPollCommand());

        // This is an invalid transition
        Command command = machine.transitionTo(BasicEnum.END);

        assertThrows(InvalidStateTransition.class, () -> CommandScheduler.getInstance().schedule(command));

        assertEquals(BasicEnum.START, machine.currentState());
    }

    /**
     * Normally, you wouldn't transitionAlways and failLoudly on the same set of states. It can be useful at times when
     * you may know a particular transition is dangerous, and it would be entirely safer to crash the robot's software
     * stack than to risk the robot doing something physically dangerous.
     */
    @Test
    void failLoudlyPreventsOtherwiseSafeTransitions() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).failLoudly();

        assertThrows(InvalidStateTransition.class, machine::poll);
    }

    @Test
    void multipleRunCommandsOnSameTransition() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean first = new AtomicBoolean(false);
        final AtomicBoolean second = new AtomicBoolean(false);

        // Set up two commands on the same transition
        machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .transitionAlways()
                .run(Commands.runOnce(() -> first.set(true)).ignoringDisable(true));

        machine
                .state(BasicEnum.START)
                .to(BasicEnum.STATE_A)
                .run(Commands.runOnce(() -> second.set(true)).ignoringDisable(true));

        machine.poll();

        // Both commands should have been scheduled
        assertTrue(first.get());
        assertTrue(second.get());
        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void multipleTransitionWhenOnSameTransition() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean firstCondition = new AtomicBoolean(false);
        final AtomicBoolean secondCondition = new AtomicBoolean(false);

        // Set up multiple conditions for the same transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionWhen(firstCondition::get);
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionWhen(secondCondition::get);

        // Neither condition is true yet
        machine.poll();
        assertEquals(BasicEnum.START, machine.currentState());

        // Enable one condition
        firstCondition.set(true);
        machine.poll();
        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void canAddTransitionWhileMachineIsRunning() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Set up first transition
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();

        machine.poll();
        assertEquals(BasicEnum.STATE_A, machine.currentState());

        // Add a new transition dynamically
        machine.state(BasicEnum.STATE_A).to(BasicEnum.STATE_B).transitionAlways();

        machine.poll();
        assertEquals(BasicEnum.STATE_B, machine.currentState());
    }

    @Test
    void addingTransitionFromCurrentStateActivatesImmediately() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        final AtomicBoolean condition = new AtomicBoolean(true);

        // Add transition while already in the starting state
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionWhen(condition::get);

        // Should transition immediately on next poll
        machine.poll();
        assertEquals(BasicEnum.STATE_A, machine.currentState());
    }

    @Test
    void triggerDeactivatesWhenLeavingState() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Create trigger for initial state
        var trigger = machine.state(BasicEnum.START).trigger();

        assertTrue(trigger.getAsBoolean());

        // Transition away from START
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();
        machine.poll();

        assertFalse(trigger.getAsBoolean());

        // Transition back to START
        var command = machine.transitionTo(BasicEnum.START);
        CommandScheduler.getInstance().schedule(command);

        assertTrue(trigger.getAsBoolean());
    }

    @Test
    void multipleTriggersSameMachine() {
        var machine = new BasicEnumStateMachine(BasicEnum.START);

        // Create triggers for different states
        var startTrigger = machine.state(BasicEnum.START).trigger();
        var aTrigger = machine.state(BasicEnum.STATE_A).trigger();

        assertTrue(startTrigger.getAsBoolean());
        assertFalse(aTrigger.getAsBoolean());

        // Transition to STATE_A
        machine.state(BasicEnum.START).to(BasicEnum.STATE_A).transitionAlways();
        machine.poll();

        // Only the STATE_A trigger should be active
        assertFalse(startTrigger.getAsBoolean());
        assertTrue(aTrigger.getAsBoolean());
    }
}
