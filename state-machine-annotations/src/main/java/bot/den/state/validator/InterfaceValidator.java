package bot.den.state.validator;

import bot.den.state.CanTransitionState;
import bot.den.state.Environment;
import bot.den.state.Util;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

public class InterfaceValidator implements Validator {
    public final List<TypeSpec> typesToWrite = new ArrayList<>();
    private final ClassName originalTypeName;
    private final ClassName wrappedTypeName;

    public InterfaceValidator(Environment environment) {
        originalTypeName = ClassName.get(environment.element());
        wrappedTypeName = Util.getUniqueClassName(originalTypeName.peerClass(originalTypeName.simpleName() + "Data"));

        typesToWrite.add(createRecordWrapper());
    }

    @Override
    public ClassName originalTypeName() {
        return originalTypeName;
    }

    @Override
    public ClassName wrappedClassName() {
        return wrappedTypeName;
    }

    @Override
    public <R> List<R> visitTopLevel(Visitor<R> visitor) {
        throw new UnsupportedOperationException("Not currently supported on interfaces");
    }

    @Override
    public <R> List<R> visitPermutations(Visitor<R> visitor) {
        throw new UnsupportedOperationException("Not currently supported on interfaces");
    }

    private TypeSpec createRecordWrapper() {
        ParameterizedTypeName canTransitionState = ParameterizedTypeName
                .get(
                        ClassName.get(CanTransitionState.class),
                        wrappedTypeName
                );

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addParameter(originalTypeName, "data")
                .build();

        MethodSpec fromRecord = MethodSpec
                .methodBuilder("fromRecord")
                .addModifiers(Modifier.STATIC)
                .addParameter(originalTypeName, "data")
                .returns(wrappedTypeName)
                .addStatement("return new $1T(data)", wrappedTypeName)
                .build();

        MethodSpec canTransitionTo = MethodSpec
                .methodBuilder("canTransitionTo")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(wrappedTypeName, "data")
                .addCode("""
                        if(this.data.getClass().equals(data.getClass()) && this.data instanceof $T transition) {
                            return transition.canTransitionTo(data);
                        }
                        return false;
                        """,
                        CanTransitionState.class)
                .build();

        return TypeSpec
                .recordBuilder(wrappedTypeName)
                .addSuperinterface(canTransitionState)
                .recordConstructor(constructor)
                .addMethod(fromRecord)
                .addMethod(canTransitionTo)
                .build();
    }
}
