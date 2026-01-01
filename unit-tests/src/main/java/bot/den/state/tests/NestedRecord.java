package bot.den.state.tests;

import bot.den.state.StateMachine;

/**
 * This class is kind of mean. It's designed to trip up assumptions throughout the code base, such as using only the
 * field name in methods that use "data" or "state" as parameters.
 * <p>
 * It's also designed to clash with NestedRecordOuter.NestedRecord when generating the data classes. The inner class in
 * the other record should clash with this record, causing the annotation to fail. This is, of course, unless they have
 * generated unique classes.
 * @param data
 */
@StateMachine
public record NestedRecord(
        BasicEnum data,
        NestedRecordOuter state
        ) {
    // This, of course, is to add insult to injury.
    record NestedRecordOuter(BasicEnum data, TwoStateEnum state) {

    }
}
