package telegram4j.tl.generator.renderer;

import telegram4j.tl.generator.Preconditions;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.*;

public class MethodRenderer<P extends BaseClassRenderer<?>> extends ExecutableRenderer<P> {

    private final TypeRef returnType;
    private final EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);

    protected MethodRenderer(P parent, String name, TypeRef returnType) {
        super(parent, name);
        this.returnType = returnType;
    }

    public MethodRenderer<P> addTypeVariables(Collection<TypeVariableRef> types) {
        if (types.isEmpty()) {
            return this;
        }

        if (stage != TYPE_VARIABLES) {
            RenderUtils.requireStage(stage, ANNOTATIONS, TYPE_VARIABLES);
            stage = TYPE_VARIABLES;

            out.append('<');
        } else {
            out.append(", ").lw();
        }

        boolean first = true;
        for (TypeVariableRef type : types) {
            if (first) {
                first = false;
            } else {
                out.append(", ").lw();
            }

            parent.appendTypeVariable(out, type);
        }
        return this;
    }

    public MethodRenderer<P> addTypeVariables(TypeVariableRef first, TypeVariableRef... rest) {
        if (stage != TYPE_VARIABLES) {
            RenderUtils.requireStage(stage, ANNOTATIONS, TYPE_VARIABLES);
            stage = TYPE_VARIABLES;

            out.append('<');
        } else {
            out.append(", ").lw();
        }

        parent.appendTypeVariable(out, first);
        for (TypeVariableRef t : rest) {
            out.append(", ").lw();
            parent.appendTypeVariable(out, t);
        }
        return this;
    }

    @Override
    public MethodRenderer<P> addAnnotation(AnnotationRenderer renderer) {
        return (MethodRenderer<P>) super.addAnnotation(renderer);
    }

    @Override
    public MethodRenderer<P> addAnnotations(Collection<? extends Type> annotations) {
        return (MethodRenderer<P>) super.addAnnotations(annotations);
    }

    @Override
    public MethodRenderer<P> addAnnotations(Type... annotations) {
        return (MethodRenderer<P>) super.addAnnotations(annotations);
    }

    @Override
    public MethodRenderer<P> addAnnotations(boolean inline, Collection<? extends Type> annotations) {
        return (MethodRenderer<P>) super.addAnnotations(inline, annotations);
    }

    @Override
    public MethodRenderer<P> addModifiers(Collection<Modifier> modifiers) {
        this.modifiers.addAll(modifiers);
        return (MethodRenderer<P>) super.addModifiers(modifiers);
    }

    @Override
    public MethodRenderer<P> addModifiers(Modifier... modifiers) {
        this.modifiers.addAll(Arrays.asList(modifiers));
        return (MethodRenderer<P>) super.addModifiers(modifiers);
    }

    @Override
    public MethodRenderer<P> addParameter(Type type, String name) {
        return (MethodRenderer<P>) super.addParameter(type, name);
    }

    @Override
    public MethodRenderer<P> addParameter(Type type, String name, boolean vararg) {
        return (MethodRenderer<P>) super.addParameter(type, name, vararg);
    }

    @Override
    public MethodRenderer<P> addExceptions(Type first, Type... rest) {
        return (MethodRenderer<P>) super.addExceptions(first, rest);
    }

    @Override
    public MethodRenderer<P> addStatement(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addStatement(code);
    }

    @Override
    public MethodRenderer<P> addStatementFormatted(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addStatementFormatted(code);
    }

    @Override
    public MethodRenderer<P> addStatement(CharSequence format, Object... args) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addStatement(format, args);
    }

    @Override
    public MethodRenderer<P> addCode(char c) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addCode(c);
    }

    @Override
    public MethodRenderer<P> addCode(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addCode(code);
    }

    @Override
    public MethodRenderer<P> addCodeFormatted(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addCodeFormatted(code);
    }

    @Override
    public MethodRenderer<P> addCode(CharSequence format, Object... args) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.addCode(format, args);
    }

    @Override
    public MethodRenderer<P> beginControlFlow(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.beginControlFlow(code);
    }

    @Override
    public MethodRenderer<P> beginControlFlow(CharSequence format, Object... args) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.beginControlFlow(format, args);
    }

    @Override
    public MethodRenderer<P> nextControlFlow(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.nextControlFlow(code);
    }

    @Override
    public MethodRenderer<P> nextControlFlow(CharSequence format, Object... args) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.nextControlFlow(format, args);
    }

    @Override
    public MethodRenderer<P> endControlFlow() {
        requireNotAbstract();
        return (MethodRenderer<P>) super.endControlFlow();
    }

    @Override
    public MethodRenderer<P> endControlFlow(CharSequence code) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.endControlFlow(code);
    }

    @Override
    public MethodRenderer<P> endControlFlow(CharSequence format, Object... args) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.endControlFlow(format, args);
    }

    @Override
    public MethodRenderer<P> incIndent() {
        requireNotAbstract();
        return (MethodRenderer<P>) super.incIndent();
    }

    @Override
    public MethodRenderer<P> incIndent(int count) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.incIndent(count);
    }

    @Override
    public MethodRenderer<P> decIndent() {
        requireNotAbstract();
        return (MethodRenderer<P>) super.decIndent();
    }

    @Override
    public MethodRenderer<P> decIndent(int count) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.decIndent(count);
    }

    @Override
    public MethodRenderer<P> ln() {
        requireNotAbstract();
        return (MethodRenderer<P>) super.ln();
    }

    @Override
    public MethodRenderer<P> ln(int count) {
        requireNotAbstract();
        return (MethodRenderer<P>) super.ln(count);
    }

    private void requireNotAbstract() {
        if (parent.kind == ClassRenderer.Kind.INTERFACE) {
            Preconditions.requireState(modifiers.contains(Modifier.DEFAULT) || modifiers.contains(Modifier.STATIC),
                    "Interface methods can not have a body");
        } else if (parent.kind == ClassRenderer.Kind.CLASS) {
            Preconditions.requireState(!modifiers.contains(Modifier.ABSTRACT), "Abstract methods can not have a body");
        }
    }

    @Override
    protected void completeStage(Stage required) {
        do {
            // [ annotations ]
            // [ modifiers ] <[type variables]> [return type & name]([parameters]) throws [exceptions] { [body] }
            if (stage == ANNOTATIONS) {
                stage = MODIFIERS;
            } else if (stage == MODIFIERS) {
                stage = PARAMETERS;

                parent.appendType(out, returnType);
                out.append(' ');
                out.append(name);
                out.append('(');
            } else if (stage == TYPE_VARIABLES) { // optional
                stage = PARAMETERS;
                out.append("> ");

                parent.appendType(out, returnType);
                out.append(' ');
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
        if (stage == MODIFIERS || stage == ANNOTATIONS) {
            stage = PARAMETERS;
            parent.appendType(out, returnType);
            out.append(' ');
            out.append(name);
            out.append('(');
        }

        if (stage == TYPE_VARIABLES) { // optional stage
            stage = PARAMETERS;
            out.append("> ");
        }

        if (stage == PARAMETERS) {
            stage = EXCEPTIONS;
            out.append(')');
        }

        boolean cantHaveBody = parent.kind == ClassRenderer.Kind.INTERFACE &&
                !modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.DEFAULT) ||
                parent.kind == ClassRenderer.Kind.CLASS && modifiers.contains(Modifier.ABSTRACT);

        if (stage == EXCEPTIONS) {
            if (cantHaveBody) {
                out.append(';').ln();
            } else {
                out.append(" {}").ln();
            }
        }

        if (stage == BODY) {
            out.decIndent().lno().append('}').ln();
        }
    }
}
