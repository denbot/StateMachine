package bot.den.state;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;

public abstract class GenerationBase {
    final ProcessingEnvironment processingEnv;
    final TypeElement annotatedElement;
    final ElementKind annotatedKind;
    final Types typeUtils;
    final Elements elementUtils;

    GenerationBase(ProcessingEnvironment processingEnv, TypeElement annotatedElement) {
        this.processingEnv = processingEnv;
        this.annotatedElement = annotatedElement;
        this.annotatedKind = annotatedElement.getKind();
        this.typeUtils = processingEnv.getTypeUtils();
        this.elementUtils = processingEnv.getElementUtils();
    }

    public abstract void generate();

    ClassName writeType(TypeSpec type) {
        String packageName = getPackageName(annotatedElement);
        JavaFile file = JavaFile.builder(packageName, type).indent("    ").build();
        try {
            file.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error("Failed to write class " + packageName + "." + type.name());
            throw new RuntimeException(e);
        }

        return ClassName.get(packageName, type.name());
    }

    void error(String error) {
        error(error, annotatedElement);
    }

    void error(String error, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, error, element);
    }

    static String getPackageName(Element e) {
        while (e != null) {
            if (e.getKind().equals(ElementKind.PACKAGE)) {
                return ((PackageElement) e).getQualifiedName().toString();
            }
            e = e.getEnclosingElement();
        }

        return null;
    }

    /**
     * @param input The input to adjust
     * @return The input with the first letter uppercase
     */
    static String ucfirst(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }
}
