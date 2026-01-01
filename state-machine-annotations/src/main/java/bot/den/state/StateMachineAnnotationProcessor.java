package bot.den.state;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Optional;
import java.util.Set;

public class StateMachineAnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Optional<? extends TypeElement> annotationOptional =
                annotations.stream()
                        .filter((te) -> te.getSimpleName().toString().equals("StateMachine"))
                        .findFirst();

        if (annotationOptional.isEmpty()) {
            return false;
        }

        TypeElement annotation = annotationOptional.get();
        roundEnv
                .getElementsAnnotatedWith(annotation)
                .forEach((element -> {
                    try {
                        var generator = new StateMachineGenerator(processingEnv, (TypeElement) element);

                        generator.generate();
                    } catch (Exception e) {
                        String message = e.getMessage();
                        if(message == null) {
                            message = e.getClass().toString();
                        }
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
        return Set.of("bot.den.state.StateMachine");
    }
}
