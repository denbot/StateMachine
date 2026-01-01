package bot.den.state.tests;

import bot.den.state.RobotState;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class RobotStateTest {
    @BeforeEach
    public void setup() {
        assertTrue(HAL.initialize(500, 0));

        // Always try to start with a disabled state on tests
        setDriverStationState(RobotState.DISABLED);
    }

    @AfterEach
    public void cleanup() {
        // This method runs after each test to reset the scheduler state
        CommandScheduler.getInstance().cancelAll();
        CommandScheduler.getInstance().run(); // Call run() to execute end() methods
    }

    @ParameterizedTest
    @MethodSource("robotStateCombinations")
    void robotTransitionWorksAsExpected(RobotState from, RobotState to) {
        var machine = new RobotRecordStateMachine(TwoStateEnum.A);

        // Tell the driver station sim what state we're in
        setDriverStationState(from);

        // This technically assumes DISABLED -> <other state> also works
        if(from != RobotState.DISABLED) {
            machine.poll();
        }

        // Verify we're in the correct starting state
        assertEquals(from, machine.currentState().robotState());

        // Update the driver station state
        setDriverStationState(to);

        // Verify we're still in the starting state
        assertEquals(from, machine.currentState().robotState());

        // Poll for changes
        machine.poll();

        // Verify we're in the correct ending state
        assertEquals(to, machine.currentState().robotState());
    }

    /**
     * This is a self-contained method that handles updating the Driver Station and ensuring the data is propagated in
     * a way that our code and the test can see it.
     *
     * @param state The state to set the driver station to
     */
    private static void setDriverStationState(RobotState state) {
        switch(state) {
            case DISABLED -> DriverStationSim.setEnabled(false);
            case AUTO -> {
                DriverStationSim.setAutonomous(true);
                DriverStationSim.setTest(false);
                DriverStationSim.setEnabled(true);
                DriverStationSim.setDsAttached(true);
            }
            case TELEOP -> {
                DriverStationSim.setAutonomous(false);
                DriverStationSim.setTest(false);
                DriverStationSim.setEnabled(true);
                DriverStationSim.setDsAttached(true);
            }
            case TEST -> {
                DriverStationSim.setTest(true);
                DriverStationSim.setAutonomous(false);
                DriverStationSim.setEnabled(true);
                DriverStationSim.setDsAttached(true);
            }
        }

        DriverStationSim.notifyNewData();
    }

    /**
     * @return Cross-product of all enum states
     */
    private static Stream<Arguments> robotStateCombinations() {
        return Arrays.stream(RobotState.values())
                .flatMap(a -> Arrays.stream(RobotState.values())
                        .map(b -> Arguments.arguments(a, b)));
    }
}
