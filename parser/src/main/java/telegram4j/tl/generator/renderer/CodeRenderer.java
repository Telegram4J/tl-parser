package telegram4j.tl.generator.renderer;

public interface CodeRenderer<P> extends CompletableRenderer<P> {

    CodeRenderer<P> addStatementFormatted(CharSequence code);

    CodeRenderer<P> addStatement(CharSequence code);

    CodeRenderer<P> addStatement(CharSequence format, Object... args);

    CodeRenderer<P> addCode(char c);

    CodeRenderer<P> addCode(CharSequence code);

    CodeRenderer<P> addCode(CharSequence format, Object... args);

    CodeRenderer<P> beginControlFlow(CharSequence code);

    CodeRenderer<P> beginControlFlow(CharSequence format, Object... args);

    CodeRenderer<P> nextControlFlow(CharSequence code);

    CodeRenderer<P> nextControlFlow(CharSequence format, Object... args);

    CodeRenderer<P> endControlFlow();

    CodeRenderer<P> endControlFlow(CharSequence code);

    CodeRenderer<P> endControlFlow(CharSequence format, Object... args);

    CodeRenderer<P> incIndent();

    CodeRenderer<P> incIndent(int count);

    CodeRenderer<P> decIndent();

    CodeRenderer<P> decIndent(int count);

    CodeRenderer<P> ln();

    CodeRenderer<P> ln(int count);
}
