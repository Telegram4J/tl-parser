package telegram4j.tl.generator.renderer;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.PROCESSING;

public class InitializerRenderer<P extends BaseClassRenderer<?>>
        extends BaseCompletableRenderer<P>
        implements CodeRenderer<P> {

    protected InitializerRenderer(P parent, boolean isStatic) {
        super(parent, PROCESSING);

        if (isStatic) {
            out.append("static {");
        } else {
            out.append('{');
        }

        out.incIndent().ln();
    }

    @Override
    protected void complete0() {
        out.decIndent();
        out.lno().append('}');
    }

    @Override
    public InitializerRenderer<P> addStatementFormatted(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        parent.formatCode(out, code);
        out.append(';').ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> addStatement(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.append(code).append(';').ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> addStatement(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, PROCESSING);
        parent.formatCode(out, format, args);
        out.append(';').ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> addCode(char c) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.append(c);
        return this;
    }

    @Override
    public InitializerRenderer<P> addCode(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.append(code);
        return this;
    }

    @Override
    public InitializerRenderer<P> addCodeFormatted(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        parent.formatCode(out, code);
        return this;
    }

    @Override
    public InitializerRenderer<P> addCode(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, PROCESSING);
        parent.formatCode(out, format, args);
        return this;
    }

    @Override
    public InitializerRenderer<P> beginControlFlow(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.append(code).incIndent().ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> beginControlFlow(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, PROCESSING);
        parent.formatCode(out, format, args);
        out.incIndent().ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> nextControlFlow(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.decIndent().append(code).incIndent().ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> nextControlFlow(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.decIndent();
        parent.formatCode(out, format, args);
        out.incIndent().ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> endControlFlow() {
        RenderUtils.requireStage(stage, PROCESSING);
        out.append('}').decIndent().ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> endControlFlow(CharSequence code) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.decIndent().append(code).ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> endControlFlow(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.decIndent();
        parent.formatCode(out, format, args);
        out.ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> incIndent() {
        RenderUtils.requireStage(stage, PROCESSING);
        out.incIndent();
        return this;
    }

    @Override
    public InitializerRenderer<P> incIndent(int count) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.incIndent(count);
        return this;
    }

    @Override
    public InitializerRenderer<P> decIndent() {
        RenderUtils.requireStage(stage, PROCESSING);
        out.decIndent();
        return this;
    }

    @Override
    public InitializerRenderer<P> decIndent(int count) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.decIndent(count);
        return this;
    }

    @Override
    public InitializerRenderer<P> ln() {
        RenderUtils.requireStage(stage, PROCESSING);
        out.ln();
        return this;
    }

    @Override
    public InitializerRenderer<P> ln(int count) {
        RenderUtils.requireStage(stage, PROCESSING);
        out.ln(count);
        return this;
    }
}
