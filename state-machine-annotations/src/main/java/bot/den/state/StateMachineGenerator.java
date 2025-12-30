package bot.den.state;

import bot.den.state.exceptions.InvalidStateTransition;
import bot.den.state.validator.EnumValidator;
import bot.den.state.validator.RecordValidator;
import bot.den.state.validator.Validator;
import com.palantir.javapoet.*;
import edu.wpi.first.wpilibj.event.EventLoop;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class StateMachineGenerator extends GenerationBase {
    private final ClassName stateMachineClassName;
    private final ClassName stateManagerClassName;
    private final ClassName stateFromClassName;
    private final ClassName stateToClassName;
    private final ClassName stateDataName;

    private final Validator validator;

    public StateMachineGenerator(ProcessingEnvironment processingEnv, TypeElement element) {
        super(processingEnv, element);

        if (element.getKind() == ElementKind.ENUM) {
            this.validator = new EnumValidator(processingEnv, element);
            this.stateDataName = validator.originalTypeName();

        } else if (element.getKind() == ElementKind.RECORD) {
            this.validator = new RecordValidator(processingEnv, element);
            this.stateDataName = validator.wrappedClassName();

        } else {
            throw new RuntimeException("The StateMachine annotation is only valid on enums and records");
        }

        ClassName annotatedClassName = (ClassName) ClassName.get(element.asType());
        String simpleStateName = annotatedClassName.simpleName();
        stateMachineClassName = annotatedClassName.peerClass(simpleStateName + "StateMachine");
        stateManagerClassName = stateMachineClassName.nestedClass(simpleStateName + "StateManager");
        stateFromClassName = annotatedClassName.peerClass(simpleStateName + "From");
        stateToClassName = annotatedClassName.peerClass(simpleStateName + "To");
    }

    public void generate() {
        if (validator instanceof RecordValidator recordValidator) {
            generateRecordWrapper(recordValidator);
        }

        TypeSpec internalStateManager = createInternalStateManager();
        generateToClass();
        generateFromClass();
        generateStateMachineClass(internalStateManager);
    }

    private void generateRecordWrapper(RecordValidator recordValidator) {
        // This is our new Data class' name.
        ClassName stateData = recordValidator.wrappedClassName();

        ParameterizedTypeName canTransitionState = ParameterizedTypeName
                .get(
                        ClassName.get(CanTransitionState.class),
                        recordValidator.wrappedClassName()
                );

        /*
         We're going to build a new wrapper around this interface class that is itself a bunch of records that
         implement that interface.
        */
        TypeSpec.Builder recordInterfaceBuilder = TypeSpec
                .interfaceBuilder(stateData)
                .addSuperinterface(canTransitionState);

        for (List<ClassName> types : recordValidator.permutations) {
            MethodSpec.Builder recordConstructor = MethodSpec
                    .constructorBuilder();

            MethodSpec.Builder canBeComparedMethodBuilder = MethodSpec
                    .methodBuilder("canTransitionTo")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(boolean.class)
                    .addParameter(stateData, "data");

            for (ClassName typeName : types) {
                // Add the type parameter to the constructor
                String fieldName = recordValidator.fieldNameMap.get(typeName);
                recordConstructor.addParameter(typeName, fieldName);

                canBeComparedMethodBuilder.addCode(
                        """
                                $T %1$s = %2$s(data);
                                if(%1$s != null && !this.%1$s.canTransitionTo(%1$s)) return false;
                                """.formatted(
                                fieldName,
                                "get" + ucfirst(fieldName)
                        ),
                        typeName
                );
            }

            canBeComparedMethodBuilder.addStatement("return true");

            ClassName nestedName = recordValidator.innerClassMap.get(types);

            TypeSpec innerClass = TypeSpec
                    .recordBuilder(nestedName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .recordConstructor(recordConstructor.build())
                    .addSuperinterface(stateData)
                    .addMethod(canBeComparedMethodBuilder.build())
                    .build();

            recordInterfaceBuilder.addType(innerClass);
        }

        // Next up, we need a helper method for each of the original record fields that help us with comparing if states can transition
        for (var recordEntry : recordValidator.fieldNameMap.entrySet()) {
            var entryType = recordEntry.getKey();
            var entryName = recordEntry.getValue();

            MethodSpec.Builder extractorMethodBuilder = MethodSpec
                    .methodBuilder("get" + ucfirst(entryName))
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(entryType)
                    .addParameter(stateData, "data");
            for (var types : recordValidator.permutations) {
                if (!Arrays.asList(types).contains(entryType)) {
                    continue;
                }

                var innerClassName = recordValidator.innerClassMap.get(types);

                extractorMethodBuilder.addStatement(
                        "if (data instanceof $T s) return s." + entryName,
                        innerClassName
                );
            }

            extractorMethodBuilder.addStatement("return null");

            recordInterfaceBuilder.addMethod(extractorMethodBuilder.build());
        }

        // We'd also like a method that converts from our original record class to the appropriate data class
        ClassName allFieldsPresentClass = recordValidator.innerClassMap.get(recordValidator.fieldTypes);

        String constructorArgs = recordValidator.fieldTypes
                .stream()
                .map(cn -> "data." + recordValidator.fieldNameMap.get(cn) + "()")
                .collect(Collectors.joining(", "));

        MethodSpec getRecordDataMethod = MethodSpec
                .methodBuilder("getRecordData")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(recordValidator.originalTypeName(), "data")
                .returns(recordValidator.wrappedClassName())
                .addStatement("return new $T(" + constructorArgs + ")", allFieldsPresentClass)
                .build();

        recordInterfaceBuilder.addMethod(getRecordDataMethod);

        writeType(recordInterfaceBuilder.build());
    }

    private TypeSpec createInternalStateManager() {
        MethodSpec whenMethod = MethodSpec
                .methodBuilder("transitionWhen")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateDataName, "fromState")
                .addParameter(stateDataName, "toState")
                .addParameter(BooleanSupplier.class, "booleanSupplier")
                .addCode("""
                                if(!$1T.this.transitionWhenMap.containsKey(fromState)) {
                                    $1T.this.transitionWhenMap.put(fromState, new $2T<>());
                                }
                                
                                var fromStateMap = $1T.this.transitionWhenMap.get(fromState);
                                
                                if(!fromStateMap.containsKey(toState)) {
                                    fromStateMap.put(toState, new $3T<>());
                                }
                                
                                fromStateMap.get(toState).add(booleanSupplier);
                                """,
                        stateMachineClassName,
                        HashMap.class,
                        ArrayList.class)
                .build();

        MethodSpec runMethod = MethodSpec
                .methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateDataName, "fromState")
                .addParameter(stateDataName, "toState")
                .addParameter(Command.class, "command")
                .addCode("""
                                if(!$1T.this.transitionCommandMap.containsKey(fromState)) {
                                    $1T.this.transitionCommandMap.put(fromState, new $2T<>());
                                }
                                
                                var fromStateMap = $1T.this.transitionCommandMap.get(fromState);
                                if(!fromStateMap.containsKey(toState)) {
                                    fromStateMap.put(toState, new $3T<>());
                                }
                                
                                fromStateMap.get(toState).add(command);
                                """,
                        stateMachineClassName,
                        HashMap.class,
                        ArrayList.class)
                .build();

        MethodSpec triggerMethod = MethodSpec
                .methodBuilder("trigger")
                .addModifiers(Modifier.PUBLIC)
                .returns(Trigger.class)
                .addParameter(EventLoop.class, "eventLoop")
                .addParameter(stateDataName, "state")
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

        MethodSpec guardInvalidTransitionMethod = MethodSpec
                .methodBuilder("guardInvalidTransition")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateDataName, "fromState")
                .addParameter(stateDataName, "toState")
                .addCode("""
                                if(! fromState.canTransitionTo(toState)) {
                                    throw new $T(fromState, toState);
                                }
                                """,
                        InvalidStateTransition.class)
                .build();

        return TypeSpec
                .classBuilder(stateManagerClassName)
                .addMethod(whenMethod)
                .addMethod(runMethod)
                .addMethod(triggerMethod)
                .addMethod(guardInvalidTransitionMethod)
                .build();
    }

    private void generateToClass() {
        FieldSpec managerField = FieldSpec
                .builder(stateManagerClassName, "manager")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        FieldSpec fromStateField = FieldSpec
                .builder(stateDataName, "fromState")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        FieldSpec toStateField = FieldSpec
                .builder(stateDataName, "toState")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateManagerClassName, "manager")
                .addParameter(stateDataName, "fromState")
                .addParameter(stateDataName, "toState")
                .addCode("""
                        this.manager = manager;
                        this.fromState = fromState;
                        this.toState = toState;
                        
                        manager.guardInvalidTransition(fromState, toState);
                        """)
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
                .builder(stateDataName, "targetState")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateManagerClassName, "manager")
                .addParameter(stateDataName, "state")
                .addStatement("this.targetState = state")
                .addStatement("this.manager = manager")
                .build();

        MethodSpec toMethod = MethodSpec
                .methodBuilder("to")
                .addModifiers(Modifier.PUBLIC)
                .returns(stateToClassName)
                .addParameter(stateDataName, "state")
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
                .builder(stateDataName, "currentState")
                .addModifiers(Modifier.PRIVATE)
                .build();

        var transitionWhenMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateDataName,
                ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        stateDataName,
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
                stateDataName,
                ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        stateDataName,
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
                stateDataName,
                ClassName.get(Trigger.class)
        );
        FieldSpec triggerMap = FieldSpec
                .builder(triggerMapType, "triggerMap")
                .addModifiers(Modifier.PRIVATE)
                .build();

        MethodSpec constructor = MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateDataName, "initialState")
                .addCode("""
                                this.currentState = initialState;
                                this.transitionWhenMap = new $2T<>();
                                this.transitionCommandMap = new $2T<>();
                                this.triggerMap = new $2T<>();
                                this.manager = new $1T();
                                """,
                        stateManagerClassName,
                        HashMap.class)
                .build();

        MethodSpec currentStateMethod = MethodSpec
                .methodBuilder("currentState")
                .addModifiers(Modifier.PUBLIC)
                .returns(stateDataName)
                .addStatement("return this.currentState")
                .build();

        MethodSpec stateMethod = MethodSpec
                .methodBuilder("state")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateDataName, "state")
                .returns(stateFromClassName)
                .addStatement(
                        """
                                return new  $T(
                                this.manager,
                                state)""",
                        stateFromClassName)
                .build();

        MethodSpec transitionToMethod = MethodSpec
                .methodBuilder("transitionTo")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(stateDataName, "state")
                .returns(Command.class)
                .addCode("""
                                return $T.runOnce(() -> updateState(state)).ignoringDisable(true);
                                """,
                        Commands.class)
                .build();

        MethodSpec runPollCommandMethod = MethodSpec
                .methodBuilder("runPollCommand")
                .addModifiers(Modifier.PUBLIC)
                .returns(Command.class)
                .addCode("""
                                return $T.run(this::poll).ignoringDisable(true);
                                """,
                        Commands.class)
                .build();

        MethodSpec pollMethod = MethodSpec
                .methodBuilder("poll")
                .addModifiers(Modifier.PUBLIC)
                .addCode("""
                                $T nextState = this.getNextState();
                                if(nextState == null) {
                                    return;
                                }
                                
                                this.updateState(nextState);
                                """,
                        stateDataName
                )
                .build();

        MethodSpec getNextStateMethod = MethodSpec
                .methodBuilder("getNextState")
                .addModifiers(Modifier.PRIVATE)
                .returns(stateDataName)
                .addCode("""
                        if (!transitionWhenMap.containsKey(currentState)) {
                            return null;
                        }
                        
                        var toMap = transitionWhenMap.get(currentState);
                        for(var entry : toMap.entrySet()) {
                            var toState = entry.getKey();
                            if(! toMap.containsKey(toState)) {
                                return null;
                            }
                            for(var supplier : toMap.get(toState)) {
                                if (supplier.getAsBoolean()) {
                                    return entry.getKey();
                                }
                            }
                        }
                        
                        return null;
                        """
                )
                .build();

        MethodSpec updateStateMethod = MethodSpec
                .methodBuilder("updateState")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(stateDataName, "nextState")
                .addCode("""
                        this.manager.guardInvalidTransition(currentState, nextState);
                        
                        runTransitionCommands(nextState);
                        
                        this.currentState = nextState;
                        """)
                .build();

        MethodSpec runTransitionCommands = MethodSpec
                .methodBuilder("runTransitionCommands")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(stateDataName, "nextState")
                .addCode("""
                        if (! transitionCommandMap.containsKey(currentState)) {
                            return;
                        }
                        
                        var toMap = transitionCommandMap.get(currentState);
                        if(! toMap.containsKey(nextState)) {
                            return;
                        }
                        
                        var commands = toMap.get(nextState);
                        for(var command : commands) {
                            command.schedule();
                        }
                        """)
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
                .addMethod(transitionToMethod)
                .addMethod(runPollCommandMethod)
                .addMethod(pollMethod)
                .addMethod(getNextStateMethod)
                .addMethod(updateStateMethod)
                .addMethod(runTransitionCommands)
                .addType(internalStateManager)
                .build();

        writeType(type);
    }
}
