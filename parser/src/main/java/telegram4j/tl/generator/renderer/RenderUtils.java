package telegram4j.tl.generator.renderer;

import telegram4j.tl.generator.Preconditions;
import telegram4j.tl.generator.renderer.CompletableRenderer.Stage;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor9;
import java.util.LinkedHashMap;
import java.util.Map;

class RenderUtils {

    private RenderUtils() {}

    protected static String operator(int cmp) {
        if (cmp == 0) {
            return "==";
        } else if (cmp > 0) {
            return ">";
        } else {
            return "<";
        }
    }

    protected static void requireStage(Stage curr, Stage req) {
        int cmp = curr.compareTo(req);
        Preconditions.requireState(cmp == 0, () -> "Unexpected stage, current " +
                curr + " " + operator(cmp) + " required " + req);
    }

    protected static void requireStage(Stage curr, Stage min, Stage max) {
        Preconditions.requireState(curr.compareTo(min) >= 0 && curr.compareTo(max) < 0, () ->
                "Unexpected stage, current " + curr + " not in range [" + min + ", " + max + ")");
    }

    protected static TypeRef from(TypeMirror typeMirror) {
        var visitor = new SimpleTypeVisitor9<TypeRef, Void>() {
            @Override
            public TypeRef visitPrimitive(PrimitiveType t, Void p) {
                switch (t.getKind()) {
                    case BOOLEAN: return PrimitiveTypeRef.BOOLEAN;
                    case BYTE: return PrimitiveTypeRef.BYTE;
                    case SHORT: return PrimitiveTypeRef.SHORT;
                    case INT: return PrimitiveTypeRef.INT;
                    case LONG: return PrimitiveTypeRef.LONG;
                    case CHAR: return PrimitiveTypeRef.CHAR;
                    case FLOAT: return PrimitiveTypeRef.FLOAT;
                    case DOUBLE: return PrimitiveTypeRef.DOUBLE;
                    default: throw new IllegalStateException();
                }
            }

            @Override
            public TypeRef visitDeclared(DeclaredType t, Void p) {
                if (t.getTypeArguments().isEmpty()) { // not a parameterized
                    TypeElement e = (TypeElement) t.asElement();
                    return ClassRef.from(e);
                }
                // TODO
                throw new IllegalArgumentException(t.toString());
            }

            @Override
            public TypeRef visitError(ErrorType t, Void p) {
                throw new IllegalStateException("Unresolved type: " + t);
            }

            @Override
            public TypeRef visitNoType(NoType t, Void p) {
                if (t.getKind() == TypeKind.VOID) return PrimitiveTypeRef.VOID;
                return visitUnknown(t, p);
            }

            @Override
            protected TypeRef defaultAction(TypeMirror e, Void p) {
                throw new IllegalArgumentException("Unexpected type mirror: " + e);
            }
        };

        return typeMirror.accept(visitor, null);
    }
}
