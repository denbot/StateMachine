package bot.den.state.validator;

import bot.den.state.CanTransitionState;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

public class EnumValidator implements Validator {
    private final ClassName originalTypeName;

    public EnumValidator(ProcessingEnvironment environment, TypeElement typeElement) {
        originalTypeName = ClassName.get(typeElement);

        var typeUtils = environment.getTypeUtils();

        ClassName transitionStateClassName = ClassName.get(CanTransitionState.class);

        var interfaces = typeElement.getInterfaces();
        for (TypeMirror i : interfaces) {
            DeclaredType declaredType = (DeclaredType) i;
            TypeElement superClass = (TypeElement) typeUtils.asElement(declaredType);
            if (superClass.getKind() != ElementKind.INTERFACE) {
                continue;  // We only care about interfaces
            }

            if (!superClass.getQualifiedName().toString().equals(transitionStateClassName.toString())) {
                continue;  // This isn't our interface
            }

            // We still need to check correctness
            var typeArguments = declaredType.getTypeArguments();
            if (typeArguments.size() != 1) {
                throw new RuntimeException("The " + transitionStateClassName.simpleName() + " interface should only have one type argument");
            }

            String sameTypeErrorMessage = transitionStateClassName.simpleName() + " parameter must be of type " + typeElement;
            TypeMirror genericParameter = typeArguments.get(0);
            if (genericParameter.getKind() != TypeKind.DECLARED) {
                throw new RuntimeException(sameTypeErrorMessage);
            }

            TypeElement interfaceImplementation = (TypeElement) typeUtils.asElement(genericParameter);
            if (!interfaceImplementation.equals(typeElement)) {
                throw new RuntimeException(sameTypeErrorMessage);
            }

            // At this point, that enum is correctly implemented
            return;
        }

        // This enum didn't have the HasStateTransitions interface
        throw new RuntimeException(transitionStateClassName + " must be implemented for " + typeElement);
    }

    @Override
    public ClassName originalTypeName() {
        return originalTypeName;
    }

    @Override
    public ClassName wrappedClassName() {
        throw new UnsupportedOperationException("Enum validator does not wrap the class name");
    }
}
