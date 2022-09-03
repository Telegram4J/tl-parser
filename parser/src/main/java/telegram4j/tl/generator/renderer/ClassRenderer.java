package telegram4j.tl.generator.renderer;

import telegram4j.tl.generator.Preconditions;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.ANNOTATIONS;
import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.PROCESSING;

@SuppressWarnings("unchecked")
public class ClassRenderer<P extends BaseClassRenderer<?>> extends BaseClassRenderer<P> {

    public static final String DEFAULT_INDENT = "\t";
    public static final int DEFAULT_LINE_WRAP = 120;

    protected final TopLevelRenderer parent;
    protected final P enclosing;

    protected ClassRenderer(ClassRef name, Kind kind, TopLevelRenderer parent, P enclosing) {
        super(name, kind, parent.out);
        this.parent = parent;
        this.enclosing = enclosing;
    }

    public static TopLevelRenderer create(ClassRef name, Kind kind) {
        return create(name, kind, DEFAULT_INDENT, DEFAULT_LINE_WRAP);
    }

    public static TopLevelRenderer create(ClassRef name, Kind kind, String indent, int lineWrap) {
        return new TopLevelRenderer(name, kind, new CharSink(indent, lineWrap));
    }

    public ClassRenderer<ClassRenderer<P>> addType(String name, Kind kind) {
        if (stage != PROCESSING) {
            requireStage(ANNOTATIONS, PROCESSING);
            completeStage(PROCESSING);
        }
        return addPending(new ClassRenderer<>(this.name.nested(name), kind, parent, this));
    }

    @Override
    public ClassRenderer<P> addStaticImports(String... staticImports) {
        parent.addStaticImports(staticImports);
        return this;
    }

    @Override
    public ClassRenderer<P> addStaticImport(String staticImport) {
        parent.addStaticImport(staticImport);
        return this;
    }

    @Override
    public InitializerRenderer<ClassRenderer<P>> addStaticInitializer() {
        Preconditions.requireState(kind == Kind.CLASS || kind == Kind.ENUM,
                "Static initializers is not allowed in " + kind);
        if (stage != PROCESSING) {
            requireStage(ANNOTATIONS, PROCESSING);
            completeStage(PROCESSING);
        }
        return addPending(new InitializerRenderer<>(this, true));
    }

    @Override
    public InitializerRenderer<ClassRenderer<P>> addInitializer() {
        Preconditions.requireState(kind == Kind.CLASS || kind == Kind.ENUM,
                "Static initializers is not allowed in " + kind);
        if (stage != PROCESSING) {
            requireStage(ANNOTATIONS, PROCESSING);
            completeStage(PROCESSING);
        }
        return addPending(new InitializerRenderer<>(this, false));
    }

    @Override
    public ClassRenderer<P> addTypeVariables(TypeVariableRef first, TypeVariableRef... rest) {
        return (ClassRenderer<P>) super.addTypeVariables(first, rest);
    }

    @Override
    public ClassRenderer<P> addTypeVariables(Collection<TypeVariableRef> types) {
        return (ClassRenderer<P>) super.addTypeVariables(types);
    }

    @Override
    public ClassRenderer<P> addModifiers(Modifier... modifiers) {
        return (ClassRenderer<P>) super.addModifiers(modifiers);
    }

    @Override
    public ClassRenderer<P> addAnnotations(Type... annotations) {
        return (ClassRenderer<P>) super.addAnnotations(annotations);
    }

    @Override
    public ClassRenderer<P> addAnnotations(Collection<? extends Type> annotations) {
        return (ClassRenderer<P>) super.addAnnotations(annotations);
    }

    @Override
    public ClassRenderer<P> addAnnotations(boolean inline, Collection<? extends Type> annotations) {
        return (ClassRenderer<P>) super.addAnnotations(inline, annotations);
    }

    @Override
    public ClassRenderer<P> addSuperType(Type type) {
        return (ClassRenderer<P>) super.addSuperType(type);
    }

    @Override
    public ClassRenderer<P> addInterface(Type type) {
        return (ClassRenderer<P>) super.addInterface(type);
    }

    @Override
    public ClassRenderer<P> addInterfaces(List<? extends Type> types) {
        return (ClassRenderer<P>) super.addInterfaces(types);
    }

    @Override
    public ClassRenderer<P> addInterfaces(Type first, Type... rest) {
        return (ClassRenderer<P>) super.addInterfaces(first, rest);
    }

    @Override
    public ClassRenderer<P> addAttribute(Type type, String name) {
        return (ClassRenderer<P>) super.addAttribute(type, name);
    }

    @Override
    public ClassRenderer<P> addAttribute(Type type, String name, CharSequence format, Object... args) {
        return (ClassRenderer<P>) super.addAttribute(type, name, format, args);
    }

    @Override
    public FieldRenderer<ClassRenderer<P>> addField(Type type, String name) {
        return (FieldRenderer<ClassRenderer<P>>) super.addField(type, name);
    }

    @Override
    public FieldRenderer<ClassRenderer<P>> addField(Type type, String name, Modifier... modifiers) {
        return (FieldRenderer<ClassRenderer<P>>) super.addField(type, name, modifiers);
    }

    @Override
    public ExecutableRenderer<ClassRenderer<P>> addConstructor() {
        return (ExecutableRenderer<ClassRenderer<P>>) super.addConstructor();
    }

    @Override
    public ExecutableRenderer<ClassRenderer<P>> addConstructor(Modifier... modifiers) {
        return (ExecutableRenderer<ClassRenderer<P>>) super.addConstructor(modifiers);
    }

    @Override
    public MethodRenderer<ClassRenderer<P>> addMethod(Type returnType, String name) {
        return (MethodRenderer<ClassRenderer<P>>) super.addMethod(returnType, name);
    }

    @Override
    public MethodRenderer<ClassRenderer<P>> addMethod(Type returnType, String name, Modifier... modifiers) {
        return (MethodRenderer<ClassRenderer<P>>) super.addMethod(returnType, name, modifiers);
    }

    @Override
    public ClassRenderer<P> addConstant(CharSequence name, CharSequence format, Object... args) {
        return (ClassRenderer<P>) super.addConstant(name, format, args);
    }

    @Override
    public ClassRenderer<P> addConstant(CharSequence name) {
        return (ClassRenderer<P>) super.addConstant(name);
    }

    @Override
    protected void appendType(CharSink out, TypeRef type, boolean vararg) {
        parent.appendType(out, type, vararg);
    }

    @Override
    protected void appendType(CharSink out, TypeRef type) {
        parent.appendType(out, type);
    }

    @Override
    protected void separate0() {
        out.ln();
    }

    @Override
    public P complete() {
        if (stage != Stage.COMPLETE) {
            completePending();

            complete0();
            stage = Stage.COMPLETE;
        }
        return enclosing;
    }

    public enum Kind {
        CLASS,
        INTERFACE,
        ENUM,
        ANNOTATION;

        protected String asKeyword() {
            if (this == ANNOTATION) {
                return "@interface";
            }
            return name().toLowerCase(Locale.US);
        }
    }
}
