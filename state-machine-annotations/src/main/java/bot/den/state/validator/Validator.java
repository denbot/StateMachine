package bot.den.state.validator;

import com.palantir.javapoet.ClassName;

public interface Validator {
    ClassName originalTypeName();

    ClassName wrappedClassName();
}
