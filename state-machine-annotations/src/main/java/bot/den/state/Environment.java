package bot.den.state;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;

public record Environment(
        ProcessingEnvironment processingEnvironment,
        RoundEnvironment roundEnvironment,
        TypeElement element
) {
    public Environment forNewElement(TypeElement element) {
        return new Environment(
                this.processingEnvironment,
                this.roundEnvironment,
                element
        );
    }

    public void writeType(TypeSpec type) {
        String packageName = getPackageName(element);
        JavaFile file = JavaFile.builder(packageName, type).indent("    ").build();
        try {
            file.writeTo(processingEnvironment.getFiler());
        } catch (IOException e) {
            error("Failed to write class " + packageName + "." + type.name());
            throw new RuntimeException(e);
        }
    }

    public void error(String error) {
        processingEnvironment.getMessager().printMessage(Diagnostic.Kind.ERROR, error, element);
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
