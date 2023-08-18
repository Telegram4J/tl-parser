/*
 * Copyright 2023 Telegram4J
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package telegram4j.tl.generator.renderer;

class CodeRendererImpl implements CodeRenderer<CharSequence> {
    private final BaseClassRenderer<?> parent;
    private final CharSink out;

    private Stage stage = Stage.PROCESSING;

    CodeRendererImpl(BaseClassRenderer<?> parent, CharSink out) {
        this.parent = parent;
        this.out = out;
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public CharSequence complete() {
        if (stage != Stage.COMPLETE) {
            stage = Stage.COMPLETE;
        }
        return out.asStringBuilder();
    }

    @Override
    public CodeRenderer<CharSequence> addStatementFormatted(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        parent.formatCode(out, code);
        out.append(';').ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> addStatement(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.append(code).append(';').ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> addStatement(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        parent.formatCode(out, format, args);
        out.append(';').ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> addCode(char c) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.append(c);
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> addCode(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.append(code);
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> addCodeFormatted(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        parent.formatCode(out, code);
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> addCode(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        parent.formatCode(out, format, args);
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> beginControlFlow(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.append(code).incIndent().ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> beginControlFlow(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        parent.formatCode(out, format, args);
        out.incIndent().ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> nextControlFlow(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.decIndent().append(code).incIndent().ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> nextControlFlow(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.decIndent();
        parent.formatCode(out, format, args);
        out.incIndent().ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> endControlFlow() {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.append('}').decIndent().ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> endControlFlow(CharSequence code) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.decIndent().append(code).ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> endControlFlow(CharSequence format, Object... args) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.decIndent();
        parent.formatCode(out, format, args);
        out.ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> incIndent() {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.incIndent();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> incIndent(int count) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.incIndent(count);
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> decIndent() {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.decIndent();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> decIndent(int count) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.decIndent(count);
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> ln() {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.ln();
        return this;
    }

    @Override
    public CodeRenderer<CharSequence> ln(int count) {
        RenderUtils.requireStage(stage, Stage.PROCESSING);

        out.ln(count);
        return this;
    }
}
