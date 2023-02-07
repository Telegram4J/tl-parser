package telegram4j.tl.generator.renderer;

import javax.lang.model.type.TypeMirror;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Objects;

public sealed interface TypeRef extends Type
        permits AnnotatedTypeRef, ArrayRef, ClassRef, ParameterizedTypeRef,
                PrimitiveTypeRef, TypeVariableRef, WildcardTypeRef {

    default TypeRef safeBox() {
        return this;
    }

    default TypeRef safeUnbox() {
        return this;
    }

    static TypeRef from(TypeMirror typeMirror) {
        return RenderUtils.from(typeMirror);
    }

    static TypeRef of(Type type) {
        Objects.requireNonNull(type);
        if (type instanceof TypeRef t) {
            return t;
        } else if (type instanceof Class<?> klass) {
            if (klass.isArray()) {
                Class<?> cl = klass;
                short dimensions = 0;
                do {
                    dimensions++;
                    cl = cl.getComponentType();
                } while (cl.isArray());

                return ArrayRef.of(of(cl), dimensions);
            } else if (klass.isPrimitive()) {
                return PrimitiveTypeRef.valueOf(klass.getName().toUpperCase(Locale.US));
            }
            return ClassRef.of(klass);
        }

        throw new IllegalArgumentException("Unexpected type: " + type);
    }
}
