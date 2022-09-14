package telegram4j.tl.generator.renderer;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.*;

public class ExecutableRenderer<P extends BaseClassRenderer<?>>
        extends BaseCompletableRenderer<P>
        implements CodeRenderer<P>, AnnotatedRenderer<P> {

    protected static final Stage PARAMETERS = new Stage(2, "PARAMETERS"),
            EXCEPTIONS = new Stage(3, "EXCEPTIONS"),
            BODY = new Stage(4, "BODY");

    protected final String name;

    protected ExecutableRenderer(P parent, String name) {
        super(parent, ANNOTATIONS);
        this.name = name;
    }

    @Override
    public ExecutableRenderer<P> addAnnotation(AnnotationRenderer renderer) {
        RenderUtils.requireStage(stage, ANNOTATIONS);
        out.append(renderer.complete());
        return this;
    }

    @Override
    public ExecutableRenderer<P> addAnnotation(Class<? extends Annotation> annotation) {
        return addAnnotations(List.of(annotation));
    }

    @Override
    public ExecutableRenderer<P> addAnnotations(Iterable<Class<? extends Annotation>> annotations) {
        RenderUtils.requireStage(stage, ANNOTATIONS);
        parent.appendAnnotations(out, annotations);
        return this;
    }

    public ExecutableRenderer<P> addModifiers(Collection<Modifier> modifiers) {
        RenderUtils.requireStage(stage, ANNOTATIONS, MODIFIERS);
        parent.appendModifiers(out, modifiers);
        return this;
    }

    public ExecutableRenderer<P> addModifiers(Modifier... modifiers) {
        RenderUtils.requireStage(stage, ANNOTATIONS, MODIFIERS);
        parent.appendModifiers(out, modifiers);
        return this;
    }

    public ExecutableRenderer<P> addParameter(Type type, String name) {
        return addParameter(type, name, false);
    }

    public ExecutableRenderer<P> addParameter(Type type, String name, boolean vararg) {
        if (stage != PARAMETERS) {
            RenderUtils.requireStage(stage, ANNOTATIONS, PARAMETERS);
            completeStage(PARAMETERS);
        } else {
            out.append(", ").lw();
        }

        parent.appendType(out, TypeRef.of(type), vararg);
        out.append(' ');
        out.append(name);
        return this;
    }

    public ExecutableRenderer<P> addExceptions(Type first, Type... rest) {
        if (stage != EXCEPTIONS) {
            RenderUtils.requireStage(stage, ANNOTATIONS, PARAMETERS);
            completeStage(EXCEPTIONS);

            out.append(" throws ");
        }

        parent.appendType(out, TypeRef.of(first));
        for (Type t : rest) {
            out.append(", ").lw();
            parent.appendType(out, TypeRef.of(t));
        }
        return this;
    }

    @Override
    public ExecutableRenderer<P> addStatementFormatted(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        parent.formatCode(out, code);
        out.append(';').ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> addStatement(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.append(code).append(';').ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> addStatement(CharSequence format, Object... args) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        parent.formatCode(out, format, args);
        out.append(';').ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> addCode(char c) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.append(c);
        return this;
    }

    @Override
    public ExecutableRenderer<P> addCode(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.append(code);
        return this;
    }

    @Override
    public ExecutableRenderer<P> addCodeFormatted(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        parent.formatCode(out, code);
        return this;
    }

    @Override
    public ExecutableRenderer<P> addCode(CharSequence format, Object... args) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        parent.formatCode(out, format, args);
        return this;
    }

    @Override
    public ExecutableRenderer<P> beginControlFlow(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.append(code).incIndent().ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> beginControlFlow(CharSequence format, Object... args) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        parent.formatCode(out, format, args);
        out.incIndent().ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> nextControlFlow(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent().append(code).incIndent().ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> nextControlFlow(CharSequence format, Object... args) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent();
        parent.formatCode(out, format, args);
        out.incIndent().ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> endControlFlow() {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent().append('}').ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> endControlFlow(CharSequence code) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent().append(code).ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> endControlFlow(CharSequence format, Object... args) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent();
        parent.formatCode(out, format, args);
        out.ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> incIndent() {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.incIndent();
        return this;
    }

    @Override
    public ExecutableRenderer<P> incIndent(int count) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.incIndent(count);
        return this;
    }

    @Override
    public ExecutableRenderer<P> decIndent() {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent();
        return this;
    }

    @Override
    public ExecutableRenderer<P> decIndent(int count) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.decIndent(count);
        return this;
    }

    @Override
    public ExecutableRenderer<P> ln() {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.ln();
        return this;
    }

    @Override
    public ExecutableRenderer<P> ln(int count) {
        if (stage != BODY) {
            RenderUtils.requireStage(stage, ANNOTATIONS, EXCEPTIONS);
            completeStage(BODY);
        }

        out.ln(count);
        return this;
    }

    protected void completeStage(Stage required) {
        do {
            // [ annotations ]
            // [ modifiers ] [return type & name]([parameters]) throws [exceptions] { [body] }
            if (stage == ANNOTATIONS) {
                stage = MODIFIERS;
            } else if (stage == MODIFIERS) {
                stage = PARAMETERS;
                out.append(name);
                out.append('(');
            } else if (stage == PARAMETERS) {
                stage = EXCEPTIONS;
                out.append(')');
            } else if (stage == EXCEPTIONS) {
                stage = BODY;
                out.incIndent();
                out.append(" {").ln();
            }
        } while (stage != required);
    }

    @Override
    protected void complete0() {
        if (stage == ANNOTATIONS || stage == MODIFIERS) {
            stage = PARAMETERS;
            out.append(name);
            out.append('(');
        }

        if (stage == PARAMETERS) {
            stage = EXCEPTIONS;
            out.append(')');
        }

        if (stage == EXCEPTIONS) {
            out.append(" {}").ln();
        }

        if (stage == BODY) {
            out.decIndent().lno().append('}').ln();
        }
    }
}