package bot.den.state;

import com.palantir.javapoet.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.function.BooleanSupplier;

public class StateMachineGenerator {
    private final ProcessingEnvironment processingEnv;
    private final Element element;

    private final String stateSimpleName;
    private final TypeName stateType;

    public StateMachineGenerator(ProcessingEnvironment processingEnv, Element element) {
        this.processingEnv = processingEnv;
        this.element = element;

        stateSimpleName = element.getSimpleName().toString();
        this.stateType = TypeName.get(element.asType());

        // Validate that we've been annotated on classes we care about
        if (element.getKind() != ElementKind.ENUM) {
            error("StateMachine annotation must be made on an enum");
            return;
        }

        Types util = processingEnv.getTypeUtils();

        // Annotation interface only allows being placed on a class, so this cast is safe.
        TypeElement typeElement = (TypeElement) element;
        var interfaces = typeElement.getInterfaces();

        boolean hasTransitionsInterface = false;
        for (TypeMirror i : interfaces) {
            if (i.getKind() != TypeKind.DECLARED) {
                continue;  // This shouldn't happen, but let's not cast without being sure
            }

            DeclaredType declaredType = (DeclaredType) i;
            TypeElement superClass = (TypeElement) util.asElement(declaredType);
            if (superClass.getKind() != ElementKind.INTERFACE) {
                continue;  // We only care about interfaces
            }

            if (!superClass.getQualifiedName().toString().equals("bot.den.state.HasStateTransitions")) {
                continue;  // This isn't our interface
            }

            // Now we know it implemented our interface, so we can ignore the message below.
            hasTransitionsInterface = true;

            // We still need to check correctness
            var typeArguments = declaredType.getTypeArguments();
            if (typeArguments.size() != 1) {
                error("The HasStateTransitions interface should only have one type argument");
                return;
            }

            String sameTypeErrorMessage = "HasStateTransitions parameter must be of type " + typeElement.getQualifiedName();
            TypeMirror genericParameter = typeArguments.get(0);
            if (genericParameter.getKind() != TypeKind.DECLARED) {
                error(sameTypeErrorMessage);
                return;
            }

            TypeElement interfaceImplementation = (TypeElement) util.asElement(genericParameter);
            if (!interfaceImplementation.equals(typeElement)) {
                error(sameTypeErrorMessage);
                return;
            }

            // At this point, that enum is correctly implemented
        }

        if (!hasTransitionsInterface) {
            error("HasStateTransitions must be implemented for " + typeElement.getQualifiedName());
            return;
        }
    }

    public void generate() {
        ClassName toClassName = generateToClass();
        ClassName fromClassName = generateFromClass(toClassName);
        generateStateMachineClass(fromClassName);
    }

    private ClassName generateToClass() {
        MethodSpec whenMethod = MethodSpec
                .methodBuilder("transitionWhen")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(BooleanSupplier.class, "booleanSupplier")
                .build();

        TypeSpec type = TypeSpec
                .classBuilder(stateSimpleName + "To")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(whenMethod)
                .build();

        writeType(type);

        return ClassName.get(getPackageName(element), type.name());
    }

    private ClassName generateFromClass(ClassName toClassName) {
        MethodSpec toMethod = MethodSpec
                .methodBuilder("to")
                .addModifiers(Modifier.PUBLIC)
                .returns(toClassName)
                .addParameter(stateType, "state")
                .addStatement("return new " + toClassName.simpleName() + "()")
                .build();

        TypeSpec type = TypeSpec
                .classBuilder(stateSimpleName + "From")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(toMethod)
                .build();

        writeType(type);

        return ClassName.get(getPackageName(element), type.name());
    }

    private void generateStateMachineClass(
            ClassName fromClassName
    ) {
        FieldSpec currentStateField = FieldSpec
                .builder(stateType, "currentState")
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateType, "initialState")
                .addStatement("this.currentState = initialState")
                .build();

        MethodSpec currentStateMethod = MethodSpec
                .methodBuilder("currentState")
                .addModifiers(Modifier.PUBLIC)
                .returns(stateType)
                .addStatement("return this.currentState")
                .build();

        MethodSpec fromMethod = MethodSpec
                .methodBuilder("from")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateType, "state")
                .returns(fromClassName)
                .addStatement("return new " + fromClassName.simpleName() + "()")
                .build();

        MethodSpec pollMethod = MethodSpec
                .methodBuilder("poll")
                .addModifiers(Modifier.PUBLIC)
                .build();

        TypeSpec type = TypeSpec
                .classBuilder(stateSimpleName + "StateMachine")
                .addModifiers(Modifier.PUBLIC)
                .addField(currentStateField)
                .addMethod(constructor)
                .addMethod(currentStateMethod)
                .addMethod(fromMethod)
                .addMethod(pollMethod)
                .build();

        writeType(type);
    }

    private void writeType(TypeSpec type) {
        String packageName = getPackageName(element);
        JavaFile file = JavaFile.builder(packageName, type).build();
        try {
            file.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error("Failed to write class " + packageName + "." + type.name());
            e.printStackTrace();
        }
    }

    private void error(String error) {
        error(error, element);
    }

    private void error(String error, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, error, element);
    }

    private static String getPackageName(Element e) {
        while (e != null) {
            if (e.getKind().equals(ElementKind.PACKAGE)) {
                return ((PackageElement) e).getQualifiedName().toString();
            }
            e = e.getEnclosingElement();
        }

        return null;
    }
}
