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
import java.util.function.BiConsumer;
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

            // canTransitionTo override
            MethodSpec.Builder canBeComparedMethodBuilder = MethodSpec
                    .methodBuilder("canTransitionTo")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(boolean.class)
                    .addParameter(stateData, "data");

            // attemptTransitionTo override
            MethodSpec.Builder attemptTransitionToBuilder = MethodSpec
                    .methodBuilder("attemptTransitionTo")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(stateData, "data")
                    .beginControlFlow("try");

            for (ClassName typeName : types) {
                // Add the type parameter to the constructor
                String fieldName = recordValidator.fieldNameMap.get(typeName);
                recordConstructor.addParameter(typeName, fieldName);

                canBeComparedMethodBuilder.addCode(
                        """
                                $3T $1L = $2L(data);
                                if($1L != null && !this.$1L.canTransitionTo($1L)) return false;
                                """,
                        fieldName,
                        "get" + ucfirst(fieldName),
                        typeName
                );

                attemptTransitionToBuilder.addCode(
                        """
                                $3T $1L = $2L(data);
                                if($1L != null) this.$1L.attemptTransitionTo($1L);""",
                        fieldName,
                        "get" + ucfirst(fieldName),
                        typeName
                );
            }

            canBeComparedMethodBuilder.addStatement("return true");
            attemptTransitionToBuilder
                    .addCode("\n")
                    .nextControlFlow("catch ($T ex)", InvalidStateTransition.class)
                    .addStatement("throw new $T(this, data, ex)", InvalidStateTransition.class)
                    .endControlFlow();


            ClassName nestedName = recordValidator.fieldToInnerClass.get(types);

            TypeSpec innerClass = TypeSpec
                    .recordBuilder(nestedName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .recordConstructor(recordConstructor.build())
                    .addSuperinterface(stateData)
                    .addMethod(canBeComparedMethodBuilder.build())
                    .addMethod(attemptTransitionToBuilder.build())
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
            for (List<ClassName> types : recordValidator.permutations) {
                if (!types.contains(entryType)) {
                    continue;
                }

                var innerClassName = recordValidator.fieldToInnerClass.get(types);

                extractorMethodBuilder.addStatement(
                        "if (data instanceof $T s) return s." + entryName,
                        innerClassName
                );
            }

            extractorMethodBuilder.addStatement("return null");

            recordInterfaceBuilder.addMethod(extractorMethodBuilder.build());
        }

        // We'd also like a method that converts from our original record class to the appropriate data class
        ClassName allFieldsPresentClass = recordValidator.fieldToInnerClass.get(recordValidator.fieldTypes);

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
                                $1T.this.verifyFromStateEnabled(fromState);
                                
                                if(!$1T.this.transitionWhenMap.containsKey(fromState)) {
                                    $1T.this.transitionWhenMap.put(fromState, new $2T<>());
                                }
                                
                                var fromStateMap = $1T.this.transitionWhenMap.get(fromState);
                                
                                if(!fromStateMap.containsKey(toState)) {
                                    fromStateMap.put(toState, new $3T<>());
                                }
                                
                                fromStateMap.get(toState).add(booleanSupplier);
                                
                                if($1T.this.currentSubData.contains(fromState)) {
                                    $1T.this.regenerateTransitionWhenCache();
                                }
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
                                $1T.this.verifyFromStateEnabled(fromState);
                                $1T.this.verifyToStateEnabled(toState);
                                
                                if(!$1T.this.transitionCommandMap.containsKey(fromState)) {
                                    $1T.this.transitionCommandMap.put(fromState, new $2T<>());
                                }
                                
                                var fromStateMap = $1T.this.transitionCommandMap.get(fromState);
                                if(!fromStateMap.containsKey(toState)) {
                                    fromStateMap.put(toState, new $3T<>());
                                }
                                
                                fromStateMap.get(toState).add(command);
                                
                                if($1T.this.currentSubData.contains(fromState)) {
                                    $1T.this.regenerateCommandCache();
                                }
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
                                $1T.this.verifyFromStateEnabled(state);
                                
                                if(! $1T.this.triggerMap.containsKey(state)) {
                                    var trigger = new Trigger(eventLoop, () -> $1T.this.currentSubData.contains(state));
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
                        
                        fromState.attemptTransitionTo(toState);
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

        List<MethodSpec> toMethods = validator.visitPermutations(new Validator.Visitor<>() {
            @Override
            public MethodSpec acceptUserDataType() {
                CodeBlock dataParameter;
                if (validator instanceof EnumValidator) {
                    dataParameter = CodeBlock.of(
                            """
                                    return new $T(
                                    this.manager,
                                    this.targetState,
                                    state
                                    )""",
                            stateToClassName);
                } else if (validator instanceof RecordValidator rv) {
                    dataParameter = CodeBlock.of("return to($T.getRecordData(state))", rv.wrappedClassName());
                } else {
                    throw new RuntimeException("Unknown validator type");
                }

                return MethodSpec
                        .methodBuilder("to")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(validator.originalTypeName(), "state")
                        .returns(stateToClassName)
                        .addStatement(dataParameter)
                        .build();
            }

            @Override
            public MethodSpec acceptFields(RecordValidator validator, List<ClassName> fields) {
                MethodSpec.Builder methodBuilder = MethodSpec
                        .methodBuilder("to")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(stateToClassName);

                for (var type : fields) {
                    methodBuilder.addParameter(type, validator.fieldNameMap.get(type));
                }

                CodeBlock code = CodeBlock
                        .builder()
                        .add("return to(\n")
                        .add(validator.emitDataClass(fields))
                        .add(");\n")
                        .build();

                return methodBuilder.addCode(code).build();
            }

            @Override
            public MethodSpec acceptWrapperDataType() {
                return MethodSpec
                        .methodBuilder("to")
                        .addModifiers(Modifier.PRIVATE)
                        .returns(stateToClassName)
                        .addParameter(validator.wrappedClassName(), "state")
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
            }
        });

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

        TypeSpec.Builder typeBuilder = TypeSpec
                .classBuilder(stateFromClassName)
                .addModifiers(Modifier.PUBLIC)
                .addField(managerField)
                .addField(targetStateField)
                .addMethod(constructor);

        for (var toMethod : toMethods) {
            typeBuilder.addMethod(toMethod);
        }
        TypeSpec type = typeBuilder
                .addMethod(triggerDefaultMethod)
                .addMethod(triggerEventLoopMethod)
                .build();

        writeType(type);
    }

    private void generateStateMachineClass(TypeSpec internalStateManager) {
        FieldSpec managerField = FieldSpec
                .builder(stateManagerClassName, "manager")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", stateManagerClassName)
                .build();

        FieldSpec currentStateField = FieldSpec
                .builder(validator.originalTypeName(), "currentState")
                .addModifiers(Modifier.PRIVATE)
                .build();

        var subDataSetType = ParameterizedTypeName.get(ClassName.get(Set.class), stateDataName);

        FieldSpec currentSubDataField = FieldSpec
                .builder(subDataSetType, "currentSubData")
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
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", HashMap.class)
                .build();

        var transitionWhenCacheType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(BooleanSupplier.class),
                stateDataName
        );

        FieldSpec transitionWhenCache = FieldSpec
                .builder(transitionWhenCacheType, "transitionWhenCache")
                .addModifiers(Modifier.PRIVATE)
                .initializer("new $T()", HashMap.class)
                .build();

        ParameterizedTypeName commandListType = ParameterizedTypeName.get(
                List.class,
                Command.class
        );
        var transitionCommandMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateDataName,
                ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        stateDataName,
                        commandListType
                )
        );

        FieldSpec transitionCommandMap = FieldSpec
                .builder(transitionCommandMapType, "transitionCommandMap")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", HashMap.class)
                .build();

        var transitionCommandCacheType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateDataName,
                commandListType
        );

        FieldSpec transitionCommandCache = FieldSpec
                .builder(transitionCommandCacheType, "transitionCommandCache")
                .addModifiers(Modifier.PRIVATE)
                .initializer("new $T()", HashMap.class)
                .build();

        var triggerMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                stateDataName,
                ClassName.get(Trigger.class)
        );

        FieldSpec triggerMap = FieldSpec
                .builder(triggerMapType, "triggerMap")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", HashMap.class)
                .build();

        final String FROM = "from";
        final String TO = "to";

        Map<String, Map<ClassName, FieldSpec>> innerClassEnabledFields = Map.ofEntries(
                Map.entry(FROM, new HashMap<>()),
                Map.entry(TO, new HashMap<>())
        );

        // Individual boolean enabled fields
        if (validator instanceof RecordValidator rv) {
            innerClassEnabledFields
                    .forEach((key, value) -> {
                        for (var innerClassName : rv.fieldToInnerClass.values()) {
                            var field = FieldSpec
                                    .builder(boolean.class, key + innerClassName.simpleName() + "Enabled")
                                    .addModifiers(Modifier.PRIVATE)
                                    .initializer("false")
                                    .build();
                            value.put(innerClassName, field);
                        }
                    });
        }

        List<MethodSpec> constructors = validator.visitTopLevel(new Validator.Visitor<>() {
            @Override
            public MethodSpec acceptUserDataType() {
                return MethodSpec
                        .constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(validator.originalTypeName(), "initialState")
                        .addStatement("this.currentState = initialState")
                        .addStatement("this.currentSubData = this.generateToSubDataStates(initialState)")
                        .build();
            }

            @Override
            public MethodSpec acceptFields(RecordValidator validator, List<ClassName> fields) {
                MethodSpec.Builder constructorBuilder = MethodSpec
                        .constructorBuilder()
                        .addModifiers(Modifier.PUBLIC);

                for (var type : fields) {
                    constructorBuilder.addParameter(type, validator.fieldNameMap.get(type));
                }

                CodeBlock.Builder code = CodeBlock
                        .builder()
                        .add("this(new $T(", validator.originalTypeName())
                        .add(validator.emitFieldNames(fields))
                        .add("));");

                constructorBuilder.addCode(code.build());

                return constructorBuilder.build();
            }

            @Override
            public MethodSpec acceptWrapperDataType() {
                // We don't need a state machine constructor that takes the data type we wrap
                return null;
            }
        });

        MethodSpec currentStateMethod = MethodSpec
                .methodBuilder("currentState")
                .addModifiers(Modifier.PUBLIC)
                .returns(validator.originalTypeName())
                .addStatement("return this.currentState")
                .build();

        List<MethodSpec> stateMethods = validator.visitPermutations(new Validator.Visitor<>() {
            @Override
            public MethodSpec acceptUserDataType() {
                CodeBlock dataParameter;
                if (validator instanceof EnumValidator) {
                    dataParameter = CodeBlock.of("state");
                } else if (validator instanceof RecordValidator rv) {
                    dataParameter = CodeBlock.of("$T.getRecordData(state)", rv.wrappedClassName());
                } else {
                    throw new RuntimeException("Unknown validator type");
                }

                return MethodSpec
                        .methodBuilder("state")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(validator.originalTypeName(), "state")
                        .returns(stateFromClassName)
                        .addStatement("return new $T(this.manager, $L)", stateFromClassName, dataParameter)
                        .build();
            }

            @Override
            public MethodSpec acceptFields(RecordValidator validator, List<ClassName> fields) {

                MethodSpec.Builder methodBuilder = MethodSpec
                        .methodBuilder("state")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(stateFromClassName);

                for (var type : fields) {
                    methodBuilder.addParameter(type, validator.fieldNameMap.get(type));
                }

                CodeBlock code = CodeBlock
                        .builder()
                        .add("return state(")
                        .add(validator.emitDataClass(fields))
                        .add(");")
                        .build();

                return methodBuilder.addCode(code).build();
            }

            @Override
            public MethodSpec acceptWrapperDataType() {
                // Internal use only for our wrapper method
                return MethodSpec
                        .methodBuilder("state")
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(validator.wrappedClassName(), "state")
                        .returns(stateFromClassName)
                        .addStatement("return new $T(this.manager, state)", stateFromClassName)
                        .build();
            }
        });

        List<MethodSpec> transitionToMethods = validator.visitPermutations(new Validator.Visitor<MethodSpec>() {
            @Override
            public MethodSpec acceptUserDataType() {
                if (validator instanceof EnumValidator) {
                    return MethodSpec
                            .methodBuilder("transitionTo")
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(validator.originalTypeName(), "state")
                            .returns(Command.class)
                            .addStatement("return $T.runOnce(() -> updateState(state)).ignoringDisable(true)", Commands.class)
                            .build();
                } else if (validator instanceof RecordValidator) {
                    /*
                    We don't make a transitionTo method for a record as the record could contain the RobotState and the
                    user should not be able to force that transition. We could theoretically check if the record had a
                    RobotState as one of its components, but then the method might "disappear" from the user's
                    perspective. We could ignore the robot state when updating our internal state, but that might be
                    confusing for the user who either expected that transition to hold or didn't know what value to put
                    for Robot State.
                     */
                    return null;
                } else {
                    throw new RuntimeException("Unknown validator type");
                }
            }

            @Override
            public MethodSpec acceptFields(RecordValidator validator, List<ClassName> fields) {
                MethodSpec.Builder methodBuilder = MethodSpec
                        .methodBuilder("transitionTo")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Command.class);

                for (var type : fields) {
                    methodBuilder.addParameter(type, validator.fieldNameMap.get(type));
                }

                CodeBlock code = CodeBlock
                        .builder()
                        .add("return transitionTo(")
                        .add(validator.emitDataClass(fields))
                        .add(");")
                        .build();

                return methodBuilder.addCode(code).build();
            }

            @Override
            public MethodSpec acceptWrapperDataType() {
                return MethodSpec
                        .methodBuilder("transitionTo")
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(validator.wrappedClassName(), "state")
                        .returns(Command.class)
                        .addCode("""
                                        return $T.runOnce(() -> updateState(state)).ignoringDisable(true);
                                        """,
                                Commands.class)
                        .build();
            }
        });

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
                        for(var supplier : this.transitionWhenCache.keySet()) {
                            if(supplier.getAsBoolean()) {
                                return this.transitionWhenCache.get(supplier);
                            }
                        }
                        
                        return null;
                        """
                )
                .build();

        MethodSpec.Builder updateStateMethodBuilder = MethodSpec
                .methodBuilder("updateState")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(stateDataName, "nextStateData");

        if (validator instanceof RecordValidator) {
            updateStateMethodBuilder.addCode("""
                            var data = $1T.getRecordData(currentState);
                            data.attemptTransitionTo(nextStateData);
                            \n""",
                    stateDataName
            );
        } else {
            updateStateMethodBuilder.addStatement("currentState.attemptTransitionTo(nextStateData)");
        }

        // Create new data instance or just assign the state manually depending on type
        if (validator instanceof EnumValidator) {
            updateStateMethodBuilder
                    .addStatement("var nextState = nextStateData");
        } else if (validator instanceof RecordValidator rv) {
            for (var type : rv.fieldTypes) {
                var fieldName = rv.fieldNameMap.get(type);
                updateStateMethodBuilder.addStatement(
                        "var $1LData = $3T.get$2L(nextStateData)",
                        fieldName,
                        ucfirst(fieldName),
                        rv.wrappedClassName()
                );
            }

            var code = CodeBlock.builder();

            code.add("$[var nextState = new $T($W", rv.originalTypeName());

            List<ClassName> fieldTypes = rv.fieldTypes;
            for (int i = 0; i < fieldTypes.size(); i++) {
                var type = fieldTypes.get(i);
                var fieldName = rv.fieldNameMap.get(type);
                code.add("$1LData == null ? currentState.$1L() : $1LData", fieldName);
                if (i + 1 != fieldTypes.size()) {
                    code.add(",");
                }
                code.add("\n");
            }

            code.add(");$]\n");

            updateStateMethodBuilder.addCode(code.build());
        }

        updateStateMethodBuilder
                .addStatement("var nextStates = generateToSubDataStates(nextState)")
                .addStatement("runTransitionCommands(nextStates)")
                .addStatement("this.currentState = nextState")
                .addStatement("this.currentSubData = generateFromSubDataStates(this.currentState)")
                .addStatement("this.regenerateTransitionWhenCache()")
                .addStatement("this.regenerateCommandCache()");

        MethodSpec updateStateMethod = updateStateMethodBuilder.build();

        MethodSpec runTransitionCommands = MethodSpec
                .methodBuilder("runTransitionCommands")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(subDataSetType, "nextStates")
                .addCode("""
                        nextStates.forEach(state -> {
                            if(! transitionCommandCache.containsKey(state)) {
                                return;
                            }
                        
                            for(var command : transitionCommandCache.get(state)) {
                                $1T.getInstance().schedule(command);
                            }
                        });
                        """, CommandScheduler.class)
                .build();

        List<MethodSpec> verifyStateEnabledMethods = new ArrayList<>();
        innerClassEnabledFields
                .forEach((key, fieldMap) -> {
                    MethodSpec.Builder verifyStateEnabledMethodBuilder = MethodSpec
                            .methodBuilder("verify" + ucfirst(key) + "StateEnabled")
                            .addModifiers(Modifier.PRIVATE)
                            .addParameter(stateDataName, "state");

                    if (validator instanceof EnumValidator) {
                        verifyStateEnabledMethodBuilder
                                .addComment("We have no states to enable, but this does make record state machine generation easier")
                                .addStatement("return");
                    } else if (validator instanceof RecordValidator) {
                        fieldMap
                                .entrySet()
                                .stream()
                                .sorted(Comparator.comparing(o -> o.getValue().name()))
                                .forEach((e) -> {
                                    verifyStateEnabledMethodBuilder.beginControlFlow("if(state instanceof $T)", e.getKey());

                                    String fieldName = e.getValue().name();
                                    if (key.equals(FROM)) {
                                        verifyStateEnabledMethodBuilder
                                                .beginControlFlow("if(!this.$L)", fieldName)
                                                .addStatement("this.$L = true", fieldName)
                                                .addStatement("this.currentSubData = this.generateFromSubDataStates(currentState)")
                                                .endControlFlow();
                                    } else {
                                        verifyStateEnabledMethodBuilder.addStatement("this.$L = true", fieldName);
                                    }

                                    verifyStateEnabledMethodBuilder
                                            .addStatement("return")
                                            .endControlFlow();
                                });
                    }

                    verifyStateEnabledMethods.add(verifyStateEnabledMethodBuilder.build());
                });

        List<MethodSpec> generateSubDataStatesMethods = new ArrayList<>();
        innerClassEnabledFields
                .forEach((key, fieldMap) -> {
                    MethodSpec.Builder generateSubDataStateBuilder = MethodSpec
                            .methodBuilder("generate" + ucfirst(key) + "SubDataStates")
                            .addModifiers(Modifier.PRIVATE)
                            .addParameter(validator.originalTypeName(), "state")
                            .returns(subDataSetType);

                    if (validator instanceof EnumValidator) {
                        generateSubDataStateBuilder
                                .addComment("Enum state machines only ever contain the one state, this does make record state machine generation easier")
                                .addStatement("return Set.of(state)");
                    } else if (validator instanceof RecordValidator rv) {
                        generateSubDataStateBuilder
                                .addStatement("$1T result = new $2T<>()", subDataSetType, HashSet.class);

                        rv.fieldNameMap.forEach(
                                (className, fieldName) -> generateSubDataStateBuilder.addStatement("$1T $2L = state.$2L()", className, fieldName)
                        );

                        fieldMap
                                .entrySet()
                                .stream()
                                .sorted(Comparator.comparing(o -> o.getValue().name()))
                                .forEach((e) -> {
                                    var innerClassName = e.getKey();
                                    var enabledField = e.getValue();

                                    generateSubDataStateBuilder
                                            .beginControlFlow("if(this.$L)", enabledField.name())
                                            .addStatement("result.add($L)", rv.emitDataClass(innerClassName))
                                            .endControlFlow();
                                });

                        generateSubDataStateBuilder
                                .addStatement("return result");
                    }

                    generateSubDataStatesMethods.add(generateSubDataStateBuilder.build());
                });

        MethodSpec regenerateTransitionWhenCacheMethod = MethodSpec
                .methodBuilder("regenerateTransitionWhenCache")
                .addModifiers(Modifier.PRIVATE)
                .addCode("""
                                this.transitionWhenCache = new $1T<>();
                                
                                this.currentSubData.forEach(state -> {
                                    if(! this.transitionWhenMap.containsKey(state)) {
                                        return;
                                    }
                                
                                
                                    for(var entry : this.transitionWhenMap.get(state).entrySet()) {
                                        for(var supplier : entry.getValue()) {
                                            this.transitionWhenCache.put(supplier, entry.getKey());
                                        }
                                    }
                                });
                                """,
                        HashMap.class)
                .build();

        MethodSpec regenerateCommandCacheMethod = MethodSpec
                .methodBuilder("regenerateCommandCache")
                .addModifiers(Modifier.PRIVATE)
                .addCode("""
                                this.transitionCommandCache = new $1T<>();
                                
                                this.currentSubData.forEach(state -> {
                                    if (!this.transitionCommandMap.containsKey(state)) {
                                        return;
                                    }
                                
                                    for(var entry : this.transitionCommandMap.get(state).entrySet()) {
                                        var toState = entry.getKey();
                                
                                        $2T commandList;
                                        if(this.transitionCommandCache.containsKey(toState)) {
                                            commandList = this.transitionCommandCache.get(toState);
                                        } else {
                                            commandList = new $3T<>();
                                            this.transitionCommandCache.put(toState, commandList);
                                        }
                                
                                        commandList.addAll(entry.getValue());
                                    }
                                });
                                """,
                        HashMap.class,
                        commandListType,
                        ArrayList.class)
                .build();

        TypeSpec.Builder typeBuilder = TypeSpec
                .classBuilder(stateMachineClassName)
                .addModifiers(Modifier.PUBLIC)
                .addField(managerField)
                .addField(currentStateField)
                .addField(currentSubDataField)
                .addField(transitionWhenMap)
                .addField(transitionWhenCache)
                .addField(transitionCommandMap)
                .addField(transitionCommandCache)
                .addField(triggerMap);

        // We want to add these boolean values to the state machine, but straight from the map they're out of order.
        innerClassEnabledFields
                .forEach((key, fieldMap) -> {
                    fieldMap
                            .values()
                            .stream()
                            .sorted(Comparator.comparing(FieldSpec::name))
                            .forEach(typeBuilder::addField);
                });

        for (var constructor : constructors) {
            typeBuilder.addMethod(constructor);
        }

        typeBuilder.addMethod(currentStateMethod);

        for (var stateMethod : stateMethods) {
            typeBuilder.addMethod(stateMethod);
        }

        for (var transitionToMethod : transitionToMethods) {
            typeBuilder.addMethod(transitionToMethod);
        }

        typeBuilder
                .addMethod(runPollCommandMethod)
                .addMethod(pollMethod)
                .addMethod(getNextStateMethod)
                .addMethod(updateStateMethod)
                .addMethod(runTransitionCommands);

        for (var verifyStateEnabledMethod : verifyStateEnabledMethods) {
            typeBuilder.addMethod(verifyStateEnabledMethod);
        }

        for (var generateSubDataStatesMethod : generateSubDataStatesMethods) {
            typeBuilder.addMethod(generateSubDataStatesMethod);
        }

        typeBuilder
                .addMethod(regenerateTransitionWhenCacheMethod)
                .addMethod(regenerateCommandCacheMethod);

        typeBuilder.addType(internalStateManager);

        writeType(typeBuilder.build());
    }
}
