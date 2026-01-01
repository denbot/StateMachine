package bot.den.state.validator;

import bot.den.state.LimitsStateTransitions;
import bot.den.state.Environment;
import com.palantir.javapoet.ClassName;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EnumValidator implements Validator {
    private final ClassName originalTypeName;
    private final boolean implementsStateTransitionInterface;

    public EnumValidator(Environment environment) {
        var typeElement = environment.element();
        originalTypeName = ClassName.get(typeElement);

        implementsStateTransitionInterface = environment.validlySelfImplements(LimitsStateTransitions.class);
    }

    @Override
    public ClassName originalTypeName() {
        return originalTypeName;
    }

    @Override
    public ClassName wrappedClassName() {
        throw new UnsupportedOperationException("Enum validator does not wrap the class name");
    }

    @Override
    public boolean supportsStateTransition() {
        return implementsStateTransitionInterface;
    }

    @Override
    public <R> List<R> visitTopLevel(Visitor<R> visitor) {
        // Just the main type is all that's needed here
        return Stream.of(
                visitor.acceptUserDataType()
        ).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public <R> List<R> visitPermutations(Visitor<R> visitor) {
        // Just the main type is all that's needed here
        return Stream.of(
                visitor.acceptUserDataType()
        ).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
