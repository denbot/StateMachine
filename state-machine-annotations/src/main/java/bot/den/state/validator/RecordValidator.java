package bot.den.state.validator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import java.util.*;
import java.util.stream.Stream;

public class RecordValidator implements Validator {
    public final List<ClassName> fieldTypes;
    public final Map<ClassName, String> fieldNameMap;
    public final List<List<ClassName>> permutations;
    public final Map<List<ClassName>, ClassName> innerClassMap;
    private final ClassName originalTypeName;
    private final ClassName wrappedTypeName;

    public RecordValidator(ProcessingEnvironment environment, TypeElement typeElement) {
        originalTypeName = ClassName.get(typeElement);
        wrappedTypeName = originalTypeName.peerClass(originalTypeName.simpleName() + "Data");

        var typeUtils = environment.getTypeUtils();

        var recordComponents = typeElement.getRecordComponents();

        if (recordComponents.isEmpty()) {
            throw new RuntimeException("An empty record isn't supported for building a state machine. Failed to build state machine for " + originalTypeName);
        }

        // Validate each enum
        var validators = recordComponents
                .stream()
                .map((e) -> {
                    var element = (TypeElement) typeUtils.asElement(e.asType());

                    if (element.getKind() == ElementKind.ENUM) {
                        return new EnumValidator(environment, element);
                    } else {
                        throw new RuntimeException("Invalid type " + element.getSimpleName() + " in record " + typeElement.getSimpleName());
                    }
                })
                .toList();

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

        innerClassMap = new HashMap<>();

        int counter = 0;
        for (var types : permutations) {
            ClassName nestedName = wrappedTypeName.nestedClass("S_" + counter);

            innerClassMap.put(types, nestedName);
            counter++;
        }
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

    public CodeBlock emitFieldNames(List<ClassName> fields) {
        CodeBlock.Builder code = CodeBlock.builder();
        for (int i = 0; i < fields.size(); i++) {
            var type = fields.get(i);
            var name = fieldNameMap.get(type);

            code.add(name);
            if (i + 1 < fields.size()) {
                code.add(", ");
            }
        }

        return code.build();
    }

    public CodeBlock emitDataClass(List<ClassName> fields) {
        return CodeBlock.builder()
                .add("new $T(", innerClassMap.get(fields))
                .add(emitFieldNames(fields))
                .add(")")
                .build();
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
                .toList();
    }

    @Override
    public <R> List<R> visitPermutations(Visitor<R> visitor) {
        List<R> result = new ArrayList<>();

        result.add(visitor.acceptUserDataType());

        for (var types : permutations) {
            result.add(visitor.acceptFields(this, types));
        }

        result.add(visitor.acceptWrapperDataType());

        return result.stream().filter(Objects::nonNull).toList();
    }
}
