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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        assertEquals(BasicEnum.START, machine.currentState());
    }

    @Test
    void whenClause() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

        // START -> END is invalid and should fail immediately
        assertThrows(
                InvalidStateTransition.class,
                () -> machine.state(BasicEnum.START).to(BasicEnum.END)
        );
    }

    @Test
    void oneTransitionAtATime() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);

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
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);
        CommandScheduler.getInstance().schedule(machine.runPollCommand());

        // This is an invalid transition
        Command command = machine.transitionTo(BasicEnum.END);

        assertThrows(InvalidStateTransition.class, command::schedule);

        assertEquals(BasicEnum.START, machine.currentState());
    }
}
