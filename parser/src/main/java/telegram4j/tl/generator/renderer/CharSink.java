package telegram4j.tl.generator.renderer;

public class CharSink {
    public static final int LINE_WRAP_INDENT = 2;

    public final String autoIndent;
    public final StringBuilder buf = new StringBuilder();
    public final int lineWrap;

    protected int column; // 0-based
    private int indentLevel;
    private boolean indent;
    private boolean lastIsLn;

    // child constr
    private CharSink(CharSink parent) {
        this.autoIndent = parent.autoIndent;
        this.lineWrap = parent.lineWrap;

        this.column = 0;
        this.indentLevel = parent.indentLevel;
        this.indent = true;
        this.lastIsLn = true;
    }

    public CharSink(String autoIndent, int lineWrap) {
        this.autoIndent = autoIndent;
        this.lineWrap = lineWrap;
    }

    private void updateLocation(int columnAdd) {
        column += columnAdd;
        lastIsLn = buf.charAt(buf.length() - 1) == '\n';
        if (lastIsLn) {
            column = 0;

            if (indentLevel != 0) {
                indent = true;
            }
        }
    }

    private void appendIndent() {
        if (indent) {
            String str = autoIndent.repeat(indentLevel);
            buf.append(str);
            column = str.length();
            indent = false;
        }
    }

    public CharSink incIndent() {
        return incIndent(1);
    }

    public int indentLevel() {
        return indentLevel;
    }

    public CharSink incIndent(int count) {
        indentLevel += count;
        return this;
    }

    public CharSink decIndent() {
        return decIndent(1);
    }

    public CharSink decIndent(int count) {
        indentLevel -= count;
        return this;
    }

    public CharSink ln(int count) {
        buf.append("\n".repeat(count));
        column = 0;
        lastIsLn = true;
        if (indentLevel != 0) {
            indent = true;
        }

        return this;
    }

    public CharSink ln() {
        buf.append('\n');
        column = 0;
        lastIsLn = true;
        if (indentLevel != 0) {
            indent = true;
        }

        return this;
    }
    // optional new line

    public CharSink lno() {
        if (!lastIsLn) {
            ln();
        }

        return this;
    }

    public CharSink lb() {
        indentLevel += LINE_WRAP_INDENT;
        ln();
        appendIndent();
        indentLevel -= LINE_WRAP_INDENT;
        return this;
    }

    public CharSink lw() {
        if (column >= lineWrap) {
            lb();
        }
        return this;
    }

    public CharSink append(Object object) {
        String str = String.valueOf(object);
        if (str.isEmpty()) {
            return this;
        }

        appendIndent();
        buf.append(str);
        updateLocation(str.length());
        return this;
    }

    public CharSink append(char c) {
        appendIndent();
        buf.append(c);
        updateLocation(1);
        return this;
    }

    public CharSink append(CharSequence str, int start, int end) {
        if (end - start == 0) {
            return this;
        }

        appendIndent();
        buf.append(str, start, end);
        updateLocation(end - start);
        return this;
    }

    public CharSink append(CharSequence str) {
        if (str.length() == 0) {
            return this;
        }

        appendIndent();
        buf.append(str);
        updateLocation(str.length());
        return this;
    }

    public StringBuilder asStringBuilder() {
        return buf;
    }

    public CharSink createChild() {
        return new CharSink(this);
    }

    public CharSink appendRaw(CharSink other) {
        buf.append(other.buf);
        column += other.buf.length();
        lastIsLn = buf.charAt(buf.length() - 1) == '\n';

        indent = other.indent;
        return this;
    }
}
