package bot.den.state;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Optional;
import java.util.Set;

public class StateMachineAnnotationProcessor extends AbstractProcessor {
    private final String stateMachineAnnotationClass = "bot.den.state.StateMachine";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Optional<? extends TypeElement> annotationOptional =
                annotations.stream()
                        .filter((te) -> te.getQualifiedName().toString().equals(stateMachineAnnotationClass))
                        .findFirst();

        if (annotationOptional.isEmpty()) {
            return false;
        }

        TypeElement annotation = annotationOptional.get();
        roundEnv
                .getElementsAnnotatedWith(annotation)
                .forEach((element -> {
                    var environment = new Environment(
                            processingEnv,
                            roundEnv,
                            (TypeElement) element
                    );

                    try {
                        var generator = new StateMachineGenerator(environment);

                        generator.generate();
                    } catch (Exception e) {
                        String message = e.getMessage();
                        if(message == null) {
                            message = e.getClass().toString();
                        }
                        environment.error(message);

                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);

                        throw new RuntimeException(e);
                    }
                }));

        return true;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
//        return Set.of(stateMachineAnnotationClass);
    }
}
