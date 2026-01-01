package bot.den.state;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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

    public boolean validlySelfImplements(Class<?> clazz) {
        return validlySelfImplements(ClassName.get(clazz));
    }

    public boolean validlySelfImplements(ClassName interfaceName) {
        var typeUtils = this.processingEnvironment.getTypeUtils();

        var interfaces = element.getInterfaces();
        for (TypeMirror i : interfaces) {
            DeclaredType declaredType = (DeclaredType) i;
            TypeElement superClass = (TypeElement) typeUtils.asElement(declaredType);
            if (superClass.getKind() != ElementKind.INTERFACE) {
                continue;  // We only care about interfaces
            }

            if (!superClass.getQualifiedName().toString().equals(interfaceName.toString())) {
                continue;  // This isn't our interface
            }

            // We still need to check correctness
            var typeArguments = declaredType.getTypeArguments();
            if (typeArguments.size() != 1) {
                throw new RuntimeException("The " + interfaceName.simpleName() + " interface should only have one type argument");
            }

            String sameTypeErrorMessage = interfaceName.simpleName() + " parameter must be of type " + element;
            TypeMirror genericParameter = typeArguments.get(0);
            if (genericParameter.getKind() != TypeKind.DECLARED) {
                throw new RuntimeException(sameTypeErrorMessage);
            }

            TypeElement interfaceImplementation = (TypeElement) typeUtils.asElement(genericParameter);
            if (!interfaceImplementation.equals(element)) {
                throw new RuntimeException(sameTypeErrorMessage);
            }

            // At this point, that type is correctly implemented
            return true;
        }

        // We didn't implement the interface at all
        return false;
    }
}
