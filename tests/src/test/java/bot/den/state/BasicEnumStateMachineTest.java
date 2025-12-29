package bot.den.state;

import bot.den.state.tests.BasicEnum;
import bot.den.state.tests.BasicEnumStateMachine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BasicEnumStateMachineTest {
    @Test
    void testBasic() {
        BasicEnumStateMachine machine = new BasicEnumStateMachine(BasicEnum.START);
    }
}
