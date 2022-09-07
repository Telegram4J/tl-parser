package telegram4j.tl.generator.renderer;

public class AnnotationRenderer implements CompletableRenderer<CharSequence> {
    private static final Stage BEGIN = new Stage(0, "BEGIN");
    private static final Stage VALUE_ATTRIBUTE = new Stage(-1, "VALUE_ATTRIBUTE");

    private final BaseClassRenderer<?> parent;
    private final CharSink out;

    private Stage stage = BEGIN;

    AnnotationRenderer(ClassRef type, BaseClassRenderer<?> parent, CharSink out) {
        this.parent = parent;
        this.out = out;

        out.append('@');
        parent.appendType(out, type);
    }

    @Override
    public Stage stage() {
        return stage;
    }

    private void appendName(CharSequence name) {
        RenderUtils.requireStage(stage, BEGIN, Stage.COMPLETE);
        if (stage != BEGIN) {
            parent.formatCode(out, ",$W ");
        } else {
            stage = Stage.PROCESSING;

            out.append('(');
        }

        out.append(name);
        out.append(" = ");

    }

    public AnnotationRenderer addAttribute(CharSequence name, Enum<?> value) {
        appendName(name);
        parent.appendType(out, ClassRef.of(value.getDeclaringClass()));
        out.append('.');
        out.append(value.name());
        return this;
    }


    public AnnotationRenderer addAttribute(CharSequence name, ClassRef value) {
        appendName(name);
        parent.appendType(out, value);
        out.append(".class");
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, Class<?> value) {
        return addAttribute(name, ClassRef.of(value));
    }

    public AnnotationRenderer addAttribute(CharSequence name, boolean value) {
        appendName(name);
        out.append(String.valueOf(value));
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, byte value) {
        appendName(name);
        out.append(String.valueOf(value));
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, short value) {
        appendName(name);
        out.append(String.valueOf(value));
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, long value) {
        appendName(name);
        out.append(String.valueOf(value));
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, float value) {
        appendName(name);
        out.append(String.valueOf(value));
        out.append('f');
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, double value) {
        appendName(name);
        out.append(String.valueOf(value));
        out.append('d');
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, int value) {
        appendName(name);
        out.append(String.valueOf(value));
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, char value) {
        appendName(name);
        out.append('\'');
        out.append(value);
        out.append('\'');
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, CharSequence value) {
        appendName(name);
        out.append('\"');
        out.append(value);
        out.append('\"');
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence name, CharSequence value, Object... args) {
        appendName(name);
        parent.formatCode(out, value, args);
        return this;
    }

    public AnnotationRenderer addAttribute(Enum<?> value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        parent.appendType(out, ClassRef.of(value.getDeclaringClass()));
        out.append('.');
        out.append(value.name());
        out.append(')');
        return this;
    }

    public AnnotationRenderer addAttribute(Class<?> value) {
        return addAttribute(ClassRef.of(value));
    }

    public AnnotationRenderer addAttribute(ClassRef value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        parent.appendType(out, value);
        out.append(".class)");
        return this;
    }

    public AnnotationRenderer addAttribute(boolean value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append(')');
        return this;
    }

    public AnnotationRenderer addAttribute(byte value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append(')');
        return this;
    }

    public AnnotationRenderer addAttribute(short value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append(')');
        return this;
    }

    public AnnotationRenderer addAttribute(long value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append(')');
        return this;
    }

    public AnnotationRenderer addAttribute(float value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append("f)");
        return this;
    }

    public AnnotationRenderer addAttribute(double value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append("d)");
        return this;
    }

    public AnnotationRenderer addAttribute(int value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append('(');
        out.append(String.valueOf(value));
        out.append(')');
        return this;
    }

    public AnnotationRenderer addAttribute(char value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append("('");
        out.append(value);
        out.append("')");
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence value) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;

        out.append("(\"");
        out.append(value);
        out.append("\")");
        return this;
    }

    public AnnotationRenderer addAttribute(CharSequence value, Object... args) {
        RenderUtils.requireStage(stage, BEGIN);
        stage = VALUE_ATTRIBUTE;
        out.append('(');
        parent.formatCode(out, value, args);
        out.append(')');
        return this;
    }

    @Override
    public CharSequence complete() {
        if (stage != Stage.COMPLETE) {
            if (stage == Stage.PROCESSING) {
                out.append(')');
            }

            out.ln();
            stage = Stage.COMPLETE;
        }
        return out.asStringBuilder();
    }
}
