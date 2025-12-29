package bot.den.state;

import com.palantir.javapoet.*;
import edu.wpi.first.wpilibj.event.EventLoop;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.button.Trigger;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

public class StateMachineGenerator {
    private final ProcessingEnvironment processingEnv;
    private final Element element;

    private final ClassName stateMachineClassName;
    private final ClassName stateManagerClassName;
    private final ClassName stateFromClassName;
    private final ClassName stateToClassName;
    private final TypeName stateType;

    public StateMachineGenerator(ProcessingEnvironment processingEnv, Element element) {
        this.processingEnv = processingEnv;
        this.element = element;

        ClassName stateClassName = (ClassName) ClassName.get(element.asType());
        String simpleStateName = stateClassName.simpleName();
        stateMachineClassName = stateClassName.peerClass(simpleStateName + "StateMachine");
        stateManagerClassName = stateMachineClassName.nestedClass(simpleStateName + "StateManager");
        stateFromClassName = stateClassName.peerClass(simpleStateName + "From");
        stateToClassName = stateClassName.peerClass(simpleStateName + "To");
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
        }
    }

    public void generate() {
        TypeSpec internalStateManager = createInternalStateManager();
        generateToClass();
        generateFromClass();
        generateStateMachineClass(internalStateManager);
    }

    private TypeSpec createInternalStateManager() {
        MethodSpec whenMethod = MethodSpec
                .methodBuilder("transitionWhen")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateType, "fromState")
                .addParameter(stateType, "toState")
                .addParameter(BooleanSupplier.class, "booleanSupplier")
                .beginControlFlow("if(!$T.this.transitionWhenMap.containsKey(fromState))", stateMachineClassName)
                .addStatement("$T.this.transitionWhenMap.put(fromState, new $T<>())", stateMachineClassName, HashMap.class)
                .endControlFlow()
                .addStatement("var fromStateMap = $T.this.transitionWhenMap.get(fromState)", stateMachineClassName)
                .beginControlFlow("if(!fromStateMap.containsKey(toState))")
                .addStatement("fromStateMap.put(toState, new $T<>())", ArrayList.class)
                .endControlFlow()
                .addStatement("var toStateMap = fromStateMap.get(toState)")
                .addStatement("toStateMap.add(booleanSupplier)")
                .build();

        MethodSpec runMethod = MethodSpec
                .methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateType, "fromState")
                .addParameter(stateType, "toState")
                .addParameter(Command.class, "command")
                .beginControlFlow("if(!$T.this.transitionCommandMap.containsKey(fromState))", stateMachineClassName)
                .addStatement("$T.this.transitionCommandMap.put(fromState, new $T<>())", stateMachineClassName, HashMap.class)
                .endControlFlow()
                .addStatement("var fromStateMap = $T.this.transitionCommandMap.get(fromState)", stateMachineClassName)
                .beginControlFlow("if(!fromStateMap.containsKey(toState))")
                .addStatement("fromStateMap.put(toState, new $T<>())", ArrayList.class)
                .endControlFlow()
                .addStatement("var toStateMap = fromStateMap.get(toState)")
                .addStatement("toStateMap.add(command)")
                .build();

        MethodSpec triggerMethod = MethodSpec
                .methodBuilder("trigger")
                .addModifiers(Modifier.PUBLIC)
                .returns(Trigger.class)
                .addParameter(EventLoop.class, "eventLoop")
                .addParameter(stateType, "state")
                .addCode("""
                                if(! $1T.this.triggerMap.containsKey(state)) {
                                    var trigger = new Trigger(eventLoop, () -> $1T.this.currentState.equals(state));
                                    triggerMap.put(state, trigger);
                                }
                                
                                return triggerMap.get(state);
                                """,
                        stateMachineClassName
                )
                .build();

        return TypeSpec
                .classBuilder(stateManagerClassName)
                .addMethod(whenMethod)
                .addMethod(runMethod)
                .addMethod(triggerMethod)
                .build();
    }

    private void generateToClass() {
        FieldSpec managerField = FieldSpec
                .builder(stateManagerClassName, "manager")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        FieldSpec fromStateField = FieldSpec
                .builder(stateType, "fromState")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        FieldSpec toStateField = FieldSpec
                .builder(stateType, "toState")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateManagerClassName, "manager")
                .addParameter(stateType, "fromState")
                .addParameter(stateType, "toState")
                .addStatement("this.manager = manager")
                .addStatement("this.fromState = fromState")
                .addStatement("this.toState = toState")
                .addStatement("var validTransitionsTo = fromState.validTransitions()")
                .beginControlFlow("if(validTransitionsTo == null || !validTransitionsTo.contains(toState))")
                .addStatement("throw new $T(fromState, toState)", InvalidStateTransition.class)
                .endControlFlow()
                .build();

        MethodSpec whenMethod = MethodSpec
                .methodBuilder("transitionWhen")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(BooleanSupplier.class, "booleanSupplier")
                .returns(stateToClassName)
                .addStatement("""
                        this.manager.transitionWhen(
                        this.fromState,
                        this.toState,
                        booleanSupplier
                        )"""
                )
                .addStatement("return this")
                .build();

        MethodSpec alwaysMethod = MethodSpec
                .methodBuilder("always")
                .addModifiers(Modifier.PUBLIC)
                .returns(stateToClassName)
                .addStatement("return transitionWhen(() -> true)")
                .build();

        MethodSpec runMethod = MethodSpec
                .methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Command.class, "command")
                .addStatement("""
                        this.manager.run(
                        this.fromState,
                        this.toState,
                        command
                        )"""
                )
                .build();

        TypeSpec type = TypeSpec
                .classBuilder(stateToClassName)
                .addModifiers(Modifier.PUBLIC)
                .addField(managerField)
                .addField(fromStateField)
                .addField(toStateField)
                .addMethod(constructor)
                .addMethod(whenMethod)
                .addMethod(alwaysMethod)
                .addMethod(runMethod)
                .build();

        writeType(type);
    }

    private void generateFromClass() {
        FieldSpec managerField = FieldSpec
                .builder(stateManagerClassName, "manager")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        FieldSpec targetStateField = FieldSpec
                .builder(stateType, "targetState")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateManagerClassName, "manager")
                .addParameter(stateType, "state")
                .addStatement("this.targetState = state")
                .addStatement("this.manager = manager")
                .build();

        MethodSpec toMethod = MethodSpec
                .methodBuilder("to")
                .addModifiers(Modifier.PUBLIC)
                .returns(stateToClassName)
                .addParameter(stateType, "state")
                .addStatement(
                        """
                                return new $T(
                                this.manager,
                                this.targetState,
                                state
                                )""",
                        stateToClassName
                )
                .build();

        MethodSpec triggerDefaultMethod = MethodSpec
                .methodBuilder("trigger")
                .addModifiers(Modifier.PUBLIC)
                .returns(Trigger.class)
                .addStatement("return this.trigger($T.getInstance().getDefaultButtonLoop())", CommandScheduler.class)
                .build();

        MethodSpec triggerEventLoopMethod = MethodSpec
                .methodBuilder("trigger")
                .addModifiers(Modifier.PUBLIC)
                .returns(Trigger.class)
                .addParameter(EventLoop.class, "eventLoop")
                .addStatement("return manager.trigger(eventLoop, targetState)")
                .build();

        TypeSpec type = TypeSpec
                .classBuilder(stateFromClassName)
                .addModifiers(Modifier.PUBLIC)
                .addField(managerField)
                .addField(targetStateField)
                .addMethod(constructor)
                .addMethod(toMethod)
                .addMethod(triggerDefaultMethod)
                .addMethod(triggerEventLoopMethod)
                .build();

        writeType(type);
    }

    private void generateStateMachineClass(TypeSpec internalStateManager) {
        FieldSpec managerField = FieldSpec
                .builder(stateManagerClassName, "manager")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        FieldSpec currentStateField = FieldSpec
                .builder(stateType, "currentState")
                .addModifiers(Modifier.PRIVATE)
                .build();

        var transitionWhenMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateType,
                ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        stateType,
                        ParameterizedTypeName.get(
                                List.class,
                                BooleanSupplier.class
                        )
                )
        );
        FieldSpec transitionWhenMap = FieldSpec
                .builder(transitionWhenMapType, "transitionWhenMap")
                .addModifiers(Modifier.PRIVATE)
                .build();

        var transitionCommandMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateType,
                ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        stateType,
                        ParameterizedTypeName.get(
                                List.class,
                                Command.class
                        )
                )
        );
        FieldSpec transitionCommandMap = FieldSpec
                .builder(transitionCommandMapType, "transitionCommandMap")
                .addModifiers(Modifier.PRIVATE)
                .build();

        var triggerMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateType,
                ClassName.get(Trigger.class)
        );
        FieldSpec triggerMap = FieldSpec
                .builder(triggerMapType, "triggerMap")
                .addModifiers(Modifier.PRIVATE)
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateType, "initialState")
                .addStatement("this.currentState = initialState")
                .addStatement("this.transitionWhenMap = new $T<>()", HashMap.class)
                .addStatement("this.transitionCommandMap = new $T<>()", HashMap.class)
                .addStatement("this.triggerMap = new $T<>()", HashMap.class)
                .addStatement("this.manager = new $T()", stateManagerClassName)
                .build();

        MethodSpec currentStateMethod = MethodSpec
                .methodBuilder("currentState")
                .addModifiers(Modifier.PUBLIC)
                .returns(stateType)
                .addStatement("return this.currentState")
                .build();

        MethodSpec stateMethod = MethodSpec
                .methodBuilder("state")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateType, "state")
                .returns(stateFromClassName)
                .addStatement(
                        """
                                return new  $T(
                                this.manager,
                                state)""",
                        stateFromClassName)
                .build();

        var optionalState = ParameterizedTypeName.get(
                ClassName.get(Optional.class),
                stateType
        );

        MethodSpec pollMethod = MethodSpec
                .methodBuilder("poll")
                .addModifiers(Modifier.PUBLIC)
                .addCode("""
                                $T nextStateOption = this.getNextState();
                                if(nextStateOption.isEmpty()) {
                                    return;
                                }
                                
                                $T nextState = nextStateOption.get();
                                """,
                        optionalState,
                        stateType
                )
                .beginControlFlow("if (transitionCommandMap.containsKey(currentState))")
                .addCode(CodeBlock
                        .builder()
                        .addStatement("var toMap = transitionCommandMap.get(currentState)")
                        .beginControlFlow("if(toMap.containsKey(nextState))")
                        .add(CodeBlock
                                .builder()
                                .addStatement("var commands = toMap.get(nextState)")
                                .beginControlFlow("for(var command : commands)")
                                .addStatement("command.schedule()")
                                .endControlFlow()
                                .build()
                        )
                        .endControlFlow()
                        .build()
                )
                .endControlFlow()
                .beginControlFlow("if (nextState != null)")
                .addStatement("this.currentState = nextState")
                .endControlFlow()
                .build();

        MethodSpec getNextStateMethod = MethodSpec
                .methodBuilder("getNextState")
                .addModifiers(Modifier.PRIVATE)
                .returns(optionalState)
                .addCode("""
                        if (!transitionWhenMap.containsKey(currentState)) {
                            return Optional.empty();
                        }
                        
                        var toMap = transitionWhenMap.get(currentState);
                        for(var entry : toMap.entrySet()) {
                            var toState = entry.getKey();
                            if(! toMap.containsKey(toState)) {
                                return Optional.empty();
                            }
                            for(var supplier : toMap.get(toState)) {
                                if (supplier.getAsBoolean()) {
                                    return Optional.of(entry.getKey());  // TODO Test issue where we can transition always to two states
                                }
                            }
                        }
                        
                        return Optional.empty();
                        """
                )
                .build();

        TypeSpec type = TypeSpec
                .classBuilder(stateMachineClassName)
                .addModifiers(Modifier.PUBLIC)
                .addField(managerField)
                .addField(currentStateField)
                .addField(transitionWhenMap)
                .addField(transitionCommandMap)
                .addField(triggerMap)
                .addMethod(constructor)
                .addMethod(currentStateMethod)
                .addMethod(stateMethod)
                .addMethod(pollMethod)
                .addMethod(getNextStateMethod)
                .addType(internalStateManager)
                .build();

        writeType(type);
    }

    private void writeType(TypeSpec type) {
        String packageName = getPackageName(element);
        JavaFile file = JavaFile.builder(packageName, type).indent("    ").build();
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
