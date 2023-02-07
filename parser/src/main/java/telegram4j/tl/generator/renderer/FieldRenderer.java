package telegram4j.tl.generator.renderer;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.*;

public class FieldRenderer<P extends BaseClassRenderer<?>>
        extends BaseCompletableRenderer<P>
        implements SeparableRenderer<P>, AnnotatedRenderer<P> {

    private static final Stage INITIALIZER = optional(1, "INITIALIZER");

    protected final TypeRef type;
    protected final String name;

    // cached for comparing in separate(T)
    private final EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);

    protected FieldRenderer(P parent, TypeRef type, String name) {
        super(parent, ANNOTATIONS);
        this.type = Objects.requireNonNull(type);
        this.name = Objects.requireNonNull(name);
    }

    private void completeStage() {
        if (stage == ANNOTATIONS || stage == MODIFIERS) {
            stage = INITIALIZER;

            parent.appendType(out, type);
            out.append(' ');
            out.append(name);
        }
    }

    @Override
    public AnnotatedRenderer<P> addAnnotation(AnnotationRenderer renderer) {
        RenderUtils.requireStage(stage, ANNOTATIONS);
        out.append(renderer.complete());
        return this;
    }

    @Override
    public FieldRenderer<P> addAnnotation(Class<? extends Annotation> annotation) {
        return addAnnotations(List.of(annotation));
    }

    @Override
    public FieldRenderer<P> addAnnotations(Iterable<Class<? extends Annotation>> annotations) {
        RenderUtils.requireStage(stage, ANNOTATIONS);
        parent.appendAnnotations(out, annotations);
        return this;
    }

    public FieldRenderer<P> addModifiers(Modifier... modifiers) {
        RenderUtils.requireStage(stage, ANNOTATIONS, MODIFIERS);
        parent.appendModifiers(out, modifiers);
        Collections.addAll(this.modifiers, modifiers);
        return this;
    }

    public FieldRenderer<P> initializer(CharSequence format, Object... args) {
        completeStage();
        RenderUtils.requireStage(stage, INITIALIZER);

        out.append(" = ");
        parent.formatCode(out, format, args);
        out.append(';').ln();
        return this;
    }

    public FieldRenderer<P> initializer(CharSequence value) {
        completeStage();
        RenderUtils.requireStage(stage, INITIALIZER);

        out.append(" = ");
        out.append(value);
        out.append(';').ln();
        return this;
    }

    @Override
    public <T extends CompletableRenderer<?>> boolean separate(T child) {
        if (!(child instanceof FieldRenderer<?> f)) {
            return true;
        }
        return !modifiers.equals(f.modifiers);
    }

    @Override
    protected void complete0() {
        if (stage == ANNOTATIONS || stage == MODIFIERS) {
            parent.appendType(out, type);
            out.append(' ');
            out.append(name);
            out.append(';').ln();
        }
    }
}
