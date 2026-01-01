package bot.den.state.validator;

import bot.den.state.CanTransitionState;
import bot.den.state.Environment;
import bot.den.state.RobotState;
import bot.den.state.Util;
import bot.den.state.exceptions.InvalidStateTransition;
import com.palantir.javapoet.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RecordValidator implements Validator {
    private static final Map<ClassName, Integer> uniqueNameCounter = new HashMap<>();

    public final List<ClassName> fieldTypes;
    public final Map<ClassName, String> fieldNameMap;
    public final List<List<ClassName>> permutations;
    public final Map<List<ClassName>, ClassName> fieldToInnerClass;
    public final Map<ClassName, List<ClassName>> innerClassToField;
    public final boolean robotStatePresent;
    public final List<TypeSpec> typesToWrite = new ArrayList<>();
    public final Map<ClassName, ClassName> nestedRecords = new HashMap<>();

    private final ClassName originalTypeName;
    private final ClassName wrappedTypeName;
    private final ClassName robotStateName;

    public RecordValidator(Environment environment) {
        var typeElement = environment.element();
        originalTypeName = ClassName.get(typeElement);

        /*
         It's necessary to eliminate the wrapping classes to simplify things significantly in other areas. The caveat
         is that we must also create a unique name. While it's unlikely to cause a conflict under typical circumstances,
         I thought it wise to avoid potential clashes, as they are technically possible. (And our unit tests are set up
         to fail if we don't)
        */
        {
            var baseClass = originalTypeName;
            while (baseClass.enclosingClassName() != null) {
                baseClass = baseClass.enclosingClassName();
            }

            ClassName uniqueDataClassName;
            String specifier = "";
            int counter = 0;
            while (true) {
                uniqueDataClassName = baseClass.peerClass(originalTypeName.simpleName() + specifier + "Data");

                if (!uniqueNameCounter.containsKey(uniqueDataClassName)) {
                    // Congrats, your name is unique
                    uniqueNameCounter.put(uniqueDataClassName, counter);
                    break;
                }

                counter++;
                specifier = String.valueOf(counter);
            }

            wrappedTypeName = uniqueDataClassName;
        }

        var typeUtils = environment.processingEnvironment().getTypeUtils();

        var recordComponents = typeElement.getRecordComponents();

        if (recordComponents.isEmpty()) {
            throw new RuntimeException("An empty record isn't supported for building a state machine. Failed to build state machine for " + originalTypeName);
        }

        // Validate each enum
        var validators = recordComponents
                .stream()
                .map((e) -> {
                    var element = (TypeElement) typeUtils.asElement(e.asType());
                    var newEnvironment = environment.forNewElement(element);

                    if (element.getKind() == ElementKind.ENUM) {
                        return new EnumValidator(newEnvironment);
                    } else if (element.getKind() == ElementKind.RECORD) {
                        // Nested record, we have to go deeper!
                        return new RecordValidator(newEnvironment);
                    } else {
                        throw new RuntimeException("Invalid type " + element.getSimpleName() + " in record " + typeElement.getSimpleName());
                    }
                })
                .toList();

        List<RecordValidator> nestedRecordValidators = validators
                .stream()
                .filter(v -> v instanceof RecordValidator)
                .map(v -> (RecordValidator) v)
                .toList();

        typesToWrite.addAll(
                nestedRecordValidators
                        .stream()
                        .flatMap(v -> ((RecordValidator) v).typesToWrite.stream())
                        .toList()
        );

        nestedRecordValidators.forEach(rv -> {
            nestedRecords.put(rv.originalTypeName, rv.wrappedTypeName);
        });

        fieldTypes = validators
                .stream()
                .map(Validator::originalTypeName)
                .toList();

        fieldNameMap = new HashMap<>();
        for (var component : recordComponents) {
            var typeName = ClassName.get((TypeElement) typeUtils.asElement(component.asType()));
            var variableName = component.getSimpleName().toString();
            fieldNameMap.put(typeName, variableName);
        }

        permutations = getPermutations(fieldTypes);

        fieldToInnerClass = new HashMap<>();
        innerClassToField = new HashMap<>();

        int counter = 0;
        for (var types : permutations) {
            ClassName nestedName = wrappedTypeName.nestedClass("S_" + counter);

            fieldToInnerClass.put(types, nestedName);
            innerClassToField.put(nestedName, types);
            counter++;
        }

        robotStateName = ClassName.get(RobotState.class);
        robotStatePresent = fieldTypes.contains(robotStateName);
        typesToWrite.add(createRecordWrapper());
    }

    /**
     * This method finds all permutations of a given input and maintains the order of the input types in its output.
     * E.g., ["A", "B", "C"] as input would give you these permutations:
     * ["A"], ["B"], ["C"]
     * ["A", "B"], ["B", "C"]
     * ["A", "B", "C"]
     *
     * @param input The list of ClassName's to find all permutations of
     * @return The permutations of all lengths
     */
    private <T> List<List<T>> getPermutations(List<T> input) {
        List<List<T>> permutations = new LinkedList<>();

        for (int numElements = 1; numElements <= input.size(); numElements++) {
            permutations.addAll(getPermutations(input, numElements));
        }

        return permutations;
    }

    private <T> List<List<T>> getPermutations(List<T> input, int length) {
        List<List<T>> permutations = new LinkedList<>();

        int counter = 0;
        int maxCounter = input.size() * length;
        main_loop:
        while (counter < maxCounter) {
            int tempCounter = counter;

            int lastIndex = Integer.MAX_VALUE;
            List<T> permutation = new ArrayList<>(length);

            // From the right side to the left, we're going to fill in our counted values
            for (int i = length - 1; i >= 0; i--) {
                int index = tempCounter % input.size();

                // If we choose a later index in our list, we'd be putting these out of order, so let's avoid this attempt
                // and try again.
                if (index >= lastIndex) {
                    counter++;
                    continue main_loop;
                }

                permutation.add(0, input.get(index));
                lastIndex = index;

                tempCounter = tempCounter / length;
            }

            permutations.add(permutation);
            counter++;
        }

        return permutations;
    }

    private CodeBlock commaSeparate(List<CodeBlock> blocks) {
        CodeBlock.Builder result = CodeBlock.builder();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            result.add(block);

            if (i + 1 < blocks.size()) {
                result.add(", ");
            }
        }

        return result.build();
    }

    public CodeBlock emitFieldNames(List<ClassName> fields, Function<String, String> transformFieldName, boolean wrapNestedClasses) {
        return commaSeparate(
                fields
                        .stream()
                        .map(type -> {
                            var fieldName = transformFieldName.apply(fieldNameMap.get(type));

                            if (type.equals(robotStateName)) {
                                return CodeBlock.of("$T.DISABLED", RobotState.class);
                            } else if (wrapNestedClasses && nestedRecords.containsKey(type)) {
                                return CodeBlock.of("$1T.fromRecord($2L)", nestedRecords.get(type), fieldName);
                            } else {
                                return CodeBlock.of(fieldName);
                            }
                        })
                        .toList()
        );
    }

    public CodeBlock emitDataClass(List<ClassName> fields) {
        return emitDataClass(fields, Function.identity());
    }

    public CodeBlock emitDataClass(List<ClassName> fields, Function<String, String> transformFieldName) {
        return CodeBlock.builder()
                .add("new $T(", fieldToInnerClass.get(fields))
                .add(emitFieldNames(fields, transformFieldName, true))
                .add(")")
                .build();
    }

    public CodeBlock emitDataClass(ClassName innerClassName, Function<String, String> transformFieldName) {
        return emitDataClass(innerClassToField.get(innerClassName), transformFieldName);
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
        return Stream.of(
                        visitor.acceptUserDataType(),
                        visitor.acceptFields(this, fieldTypes),
                        visitor.acceptWrapperDataType()
                )
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public <R> List<R> visitPermutations(Visitor<R> visitor) {
        List<R> result = new ArrayList<>();

        result.add(visitor.acceptUserDataType());

        for (var types : permutations) {
            result.add(visitor.acceptFields(this, types));
        }

        result.add(visitor.acceptWrapperDataType());

        return result.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    private TypeSpec createRecordWrapper() {
        ParameterizedTypeName canTransitionState = ParameterizedTypeName
                .get(
                        ClassName.get(CanTransitionState.class),
                        wrappedTypeName
                );

        /*
         We're going to build a new wrapper around this interface class that is itself a bunch of records that
         implement that interface.
        */
        TypeSpec.Builder recordInterfaceBuilder = TypeSpec
                .interfaceBuilder(wrappedTypeName)
                .addSuperinterface(canTransitionState);

        for (List<ClassName> types : permutations) {
            MethodSpec.Builder recordConstructor = MethodSpec
                    .constructorBuilder();

            // canTransitionTo override
            MethodSpec.Builder canBeComparedMethodBuilder = MethodSpec
                    .methodBuilder("canTransitionTo")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(boolean.class)
                    .addParameter(wrappedTypeName, "data");

            // attemptTransitionTo override
            MethodSpec.Builder attemptTransitionToBuilder = MethodSpec
                    .methodBuilder("attemptTransitionTo")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(wrappedTypeName, "data")
                    .beginControlFlow("try");

            // Inner classes that hold subsets of our data for easy passing around and manipulation
            for (ClassName typeName : types) {
                // Add the type parameter to the constructor
                String fieldName = fieldNameMap.get(typeName);
                var dataTypeName = nestedRecords.getOrDefault(typeName, typeName);

                recordConstructor.addParameter(dataTypeName, fieldName);

                canBeComparedMethodBuilder.addCode(
                        """
                                $3T $1LField = $2L(data);
                                if($1LField != null && !this.$1L.canTransitionTo($1LField)) return false;
                                """,
                        fieldName,
                        "get" + Util.ucfirst(fieldName),
                        dataTypeName
                );

                attemptTransitionToBuilder.addCode(
                        """
                                $3T $1LField = $2L(data);
                                if($1LField != null) this.$1L.attemptTransitionTo($1LField);
                                """,
                        fieldName,
                        "get" + Util.ucfirst(fieldName),
                        dataTypeName
                );
            }

            canBeComparedMethodBuilder.addStatement("return true");
            attemptTransitionToBuilder
                    .addCode("\n")
                    .nextControlFlow("catch ($T ex)", InvalidStateTransition.class)
                    .addStatement("throw new $T(this, data, ex)", InvalidStateTransition.class)
                    .endControlFlow();


            ClassName nestedName = fieldToInnerClass.get(types);

            TypeSpec innerClass = TypeSpec
                    .recordBuilder(nestedName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .recordConstructor(recordConstructor.build())
                    .addSuperinterface(wrappedTypeName)
                    .addMethod(canBeComparedMethodBuilder.build())
                    .addMethod(attemptTransitionToBuilder.build())
                    .build();

            recordInterfaceBuilder.addType(innerClass);
        }

        // Next up, we need a helper method for each of the original record fields that help us with comparing if states can transition
        {
            for (var recordEntry : fieldNameMap.entrySet()) {
                var entryType = recordEntry.getKey();
                var dataTypeName = nestedRecords.getOrDefault(entryType, entryType);
                var entryName = recordEntry.getValue();

                MethodSpec.Builder extractorMethodBuilder = MethodSpec
                        .methodBuilder("get" + Util.ucfirst(entryName))
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(dataTypeName)
                        .addParameter(wrappedTypeName, "data");
                for (List<ClassName> types : permutations) {
                    if (!types.contains(entryType)) {
                        continue;
                    }

                    var innerClassName = fieldToInnerClass.get(types);

                    extractorMethodBuilder.addStatement(
                            "if (data instanceof $T s) return s." + entryName,
                            innerClassName
                    );
                }

                extractorMethodBuilder.addStatement("return null");

                recordInterfaceBuilder.addMethod(extractorMethodBuilder.build());
            }
        }

        // We need this in the toData / fromRecord classes
        ClassName allFieldsPresentClass = fieldToInnerClass.get(fieldTypes);

        // getRecord: User record class -> Our data class
        {
            List<CodeBlock> arguments = fieldTypes
                    .stream()
                    .map(cn -> {
                        String fieldName = fieldNameMap.get(cn);
                        if (nestedRecords.containsKey(cn)) {
                            var nestedDataType = nestedRecords.get(cn);
                            return CodeBlock.of("$1T.fromRecord(record.$2L())", nestedDataType, fieldName);
                        }
                        return CodeBlock.of("record.$1L()", fieldName);
                    })
                    .toList();

            MethodSpec fromRecordMethod = MethodSpec
                    .methodBuilder("fromRecord")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(originalTypeName, "record")
                    .returns(wrappedTypeName)
                    .addStatement("return new $1T($2L)", allFieldsPresentClass, commaSeparate(arguments))
                    .build();

            recordInterfaceBuilder.addMethod(fromRecordMethod);
        }

        // toRecord: Our data class -> User record class
        {
            List<CodeBlock> arguments = fieldTypes
                    .stream()
                    .map(cn -> {
                        String fieldName = fieldNameMap.get(cn);
                        if (nestedRecords.containsKey(cn)) {
                            var nestedDataType = nestedRecords.get(cn);
                            return CodeBlock.of("$1T.toRecord(castData.$2L())", nestedDataType, fieldName);
                        }
                        return CodeBlock.of("castData.$1L()", fieldName);
                    })
                    .toList();


            // This should actually start by crashing if it's not the `allFieldsPresentClass`
            // Then it should cast it to a new variable
            MethodSpec toRecordMethod = MethodSpec
                    .methodBuilder("toRecord")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(wrappedTypeName, "data")
                    .returns(originalTypeName)
                    .beginControlFlow("if (data instanceof $T)", allFieldsPresentClass)
                    .addStatement("throw new $1T(\"Should not have tried converting this class to a record, we don't have all the information required\")", RuntimeException.class)
                    .endControlFlow()
                    .addStatement("$1T castData = ($1T) data", allFieldsPresentClass)
                    .addStatement("return new $1T($2L)", originalTypeName, commaSeparate(arguments))
                    .build();

            recordInterfaceBuilder.addMethod(toRecordMethod);
        }

        return recordInterfaceBuilder.build();
    }
}
