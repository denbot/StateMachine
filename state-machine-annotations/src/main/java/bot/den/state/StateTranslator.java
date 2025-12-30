package bot.den.state;

import com.palantir.javapoet.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.*;
import java.util.stream.Collectors;

public class StateTranslator extends GenerationBase {

    final TypeName stateType;
    private final ClassName annotatedClassName;
    private final ClassName transitionStateClassName;

    StateTranslator(ProcessingEnvironment processingEnv, TypeElement element) {
        super(processingEnv, element);

        this.annotatedClassName = (ClassName) ClassName.get(element.asType());
        transitionStateClassName = ClassName.get(CanTransitionState.class);

        // Validate that we've been annotated on classes we care about
        Optional<TypeName> typeNameOptional = switch (element.getKind()) {
            case ENUM -> validateEnum(element);
            case RECORD -> validateRecord(element);
            default -> Optional.empty();
        };

        if (typeNameOptional.isEmpty()) {
            throw new RuntimeException("Failed to validate " + element.getQualifiedName());
        }

        stateType = typeNameOptional.get();
    }

    private Optional<TypeName> validateRecord(TypeElement typeElement) {
        var recordComponents = typeElement.getRecordComponents();

        if (recordComponents.isEmpty()) {
            error("An empty record isn't supported for building a state machine", annotatedElement);
            return Optional.empty();
        }

        // Validate each enum
        var optionalStates = recordComponents
                .stream()
                .map((e) -> this.validateEnum((TypeElement) typeUtils.asElement(e.asType())))
                .toList();

        // Unwrap the list of validated enums or fail if something did not validate
        TypeName[] recordTypes = optionalStates
                .stream()
                .map(optional -> {
                    if (optional.isEmpty()) {
                        throw new RuntimeException("Failed to validate all record values for " + typeElement.getQualifiedName());
                    }
                    return optional.get();
                })
                .toArray(TypeName[]::new);

        Map<TypeName, String> recordNameMap = new HashMap<>();
        for (var component : recordComponents) {
            var typeName = TypeName.get(component.asType());
            var variableName = component.getSimpleName().toString();
            recordNameMap.put(typeName, variableName);
        }

        List<TypeName[]> permutations = getPermutations(recordTypes);

        // Make a new wrapper class with a whole mess of internal classes.
        // First, we need to generate all possible combinations of this record types list.

        ClassName stateData = annotatedClassName.peerClass(
                typeElement.getSimpleName() + "Data"
        );
        ParameterizedTypeName canTransitionState = ParameterizedTypeName
                .get(
                        transitionStateClassName,
                        stateData
                );

        /*
         We're going to build a new wrapper around this interface class that is itself a bunch of records that
         implement that interface.
        */
        TypeSpec.Builder recordInterfaceBuilder = TypeSpec
                .interfaceBuilder(stateData)
                .addSuperinterface(canTransitionState);

        // TODO Figure out how to get access to this later as we'll definitely need it. I'm thinking refactor out an
        //  Enum and Record translator that each has their own data / contents. A lot of that depends on how it ends
        //  up being used and what nested records look like.
        Map<TypeName[], ClassName> innerClassMap = new HashMap<>();

        int counter = 0;
        for (var types : permutations) {

            MethodSpec.Builder recordConstructor = MethodSpec
                    .constructorBuilder();

            MethodSpec.Builder canBeComparedMethodBuilder = MethodSpec
                    .methodBuilder("canTransitionTo")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(boolean.class)
                    .addParameter(stateData, "data");

            for (var typeName : types) {
                // Add the type parameter to the constructor
                String fieldName = recordNameMap.get(typeName);
                recordConstructor.addParameter(typeName, fieldName);

                var optionalType = ParameterizedTypeName.get(
                        ClassName.get(Optional.class),
                        typeName
                );

                canBeComparedMethodBuilder.addCode(
                        """
                                $T %1$sOptional = %2$s(data);
                                if(%1$sOptional.isPresent() && !this.%1$s.canTransitionTo(%1$sOptional.get())) {
                                    return false;
                                }
                                """.formatted(
                                fieldName,
                                "get" + ucfirst(fieldName)
                        ),
                        optionalType
                );
            }

            canBeComparedMethodBuilder.addStatement("return true");

            ClassName nestedName = stateData.nestedClass("S_" + counter);
            TypeSpec innerClass = TypeSpec
                    .recordBuilder(nestedName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .recordConstructor(recordConstructor.build())
                    .addSuperinterface(stateData)
                    .addMethod(canBeComparedMethodBuilder.build())
                    .build();

            recordInterfaceBuilder.addType(innerClass);

            innerClassMap.put(types, nestedName);

            counter++;
        }

        // Next up, we need a helper method for each of the original record fields that help us with comparing if states can transition
        for (var recordEntry : recordNameMap.entrySet()) {
            var entryType = recordEntry.getKey();
            var entryName = recordEntry.getValue();

            var optionalType = ParameterizedTypeName.get(
                    ClassName.get(Optional.class),
                    entryType
            );

            MethodSpec.Builder extractorMethodBuilder = MethodSpec
                    .methodBuilder("get" + ucfirst(entryName))
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(optionalType)
                    .addParameter(stateData, "data");
            for (var types : permutations) {
                if (!Arrays.asList(types).contains(entryType)) {
                    continue;
                }

                var innerClassName = innerClassMap.get(types);

                extractorMethodBuilder.addStatement(
                        "if (data instanceof $T s) return $T.of(s." + entryName + ")",
                        innerClassName,
                        Optional.class
                );
            }

            extractorMethodBuilder.addStatement("return Optional.empty()");

            recordInterfaceBuilder.addMethod(extractorMethodBuilder.build());
        }

        return Optional.of(writeType(recordInterfaceBuilder.build()));
    }

    private Optional<TypeName> validateEnum(TypeElement typeElement) {
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
                error("The " + transitionStateClassName.simpleName() + " interface should only have one type argument", typeElement);
                return Optional.empty();
            }

            String sameTypeErrorMessage = transitionStateClassName.simpleName() + " parameter must be of type " + typeElement.getQualifiedName();
            TypeMirror genericParameter = typeArguments.get(0);
            if (genericParameter.getKind() != TypeKind.DECLARED) {
                error(sameTypeErrorMessage, typeElement);
                return Optional.empty();
            }

            TypeElement interfaceImplementation = (TypeElement) typeUtils.asElement(genericParameter);
            if (!interfaceImplementation.equals(typeElement)) {
                error(sameTypeErrorMessage, typeElement);
                return Optional.empty();
            }

            // At this point, that enum is correctly implemented
            return Optional.of(TypeName.get(typeElement.asType()));
        }

        // This enum didn't have the HasStateTransitions interface
        error(transitionStateClassName.simpleName() + " must be implemented for " + typeElement.getQualifiedName(), typeElement);
        return Optional.empty();
    }

    /**
     * This method finds all permutations of a given input and maintains the order of the input types in its output.
     * E.g., ["A", "B", "C"] as input would give you these permutations:
     * ["A"], ["B"], ["C"]
     * ["A", "B"], ["B", "C"]
     * ["A", "B", "C"]
     *
     * @param input The list of TypeName's to find all permutations of
     * @return The permutations of all lengths
     */
    private List<TypeName[]> getPermutations(TypeName[] input) {
        List<TypeName[]> permutations = new LinkedList<>();

        for (int numElements = 1; numElements <= input.length; numElements++) {
            permutations.addAll(getPermutations(input, numElements));
        }

        return permutations;
    }

    private List<TypeName[]> getPermutations(TypeName[] input, int length) {
        List<TypeName[]> permutations = new LinkedList<>();

        int counter = 0;
        int maxCounter = input.length * length;
        main_loop:
        while (counter < maxCounter) {
            int tempCounter = counter;

            int lastIndex = Integer.MAX_VALUE;
            TypeName[] permutation = new TypeName[length];

            // From the right side to the left, we're going to fill in our counted values
            for (int i = length - 1; i >= 0; i--) {
                int index = tempCounter % input.length;

                // If we choose a later index in our list, we'd be putting these out of order, so let's avoid this attempt
                // and try again.
                if (index >= lastIndex) {
                    counter++;
                    continue main_loop;
                }

                permutation[i] = input[index];
                lastIndex = index;

                tempCounter = tempCounter / length;
            }

            permutations.add(permutation);
            counter++;
        }

        return permutations;
    }
}
