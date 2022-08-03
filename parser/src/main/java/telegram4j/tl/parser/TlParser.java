package telegram4j.tl.parser;

import reactor.util.annotation.Nullable;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

public class TlParser implements Closeable {

    static final int IN_PARAMS = 1; // in params section; changes behavior of NAME, TYPE_NAME tokens
    static final int COMPLETED = 1 << 1; // type processing complete

    private final Reader reader;
    private final char[] input;
    private final StringBuilder text = new StringBuilder();

    private int inputPos;
    private int inputEnd;
    private Kind kind;
    private Token curr;
    private Token next;
    private int state;

    private int inputProcessed; // absolute position
    private int inputRow; // line
    private int inputColumn; // position at line
    private int inputColumnStart; // start of the line

    public TlParser(Reader reader) {
        this(reader, 512);
    }

    public TlParser(Reader reader, int bufferSize) {
        this.reader = reader;
        this.input = new char[bufferSize];
    }

    @Nullable
    public Token nextToken() throws IOException {
        if (next != null) {
            Token t = next;
            next = null;
            curr = t;
            return t;
        }

        if (inputPos >= inputEnd && !loadMore()) {
            handleEOF();
            return null;
        }

        text.setLength(0);
        Token t = null;
        char c;
        wsLoop:
        while (true) {
            if (inputPos >= inputEnd) {
                if (!loadMore()) {
                    handleEOF();
                    return null;
                }
            }

            c = input[inputPos++];
            switch (c) {
                case '=':
                    if ((state & IN_PARAMS) == 0) { // no params in type
                        continue;
                    }
                    state &= ~IN_PARAMS;
                    return curr = Token.PARAMETERS_END;
                case '/':
                    if (inputPos + 1 >= inputEnd) {
                        if (!loadMore()) {
                            handleEOF();
                            return null;
                        }
                    }
                    skipComment();
                    break;
                case '\r':
                case '\n':
                    inputRow++;
                    inputColumn = 0;
                    inputColumnStart = inputPos;
                case ' ':
                    break;
                default:
                    break wsLoop;
            }
        }

        boolean allowSpace = false;
        inputPos -= 1;
        int start = inputPos;
        loop:
        while (true) {
            if (inputPos >= inputEnd) {
                return curr = nextToken0(start);
            }

            c = input[inputPos++];
            if (c == '\n' || c == '\r') {
                inputRow++;
                throw createException(inputPos - 1, "Unexpected new line in the type declaration");
            }

            if ((state & IN_PARAMS) == 0) {
                switch (c) {
                    case '{':
                        if (curr == Token.ID) {
                            // ex: {t:Type}
                            // no format verification, just skip
                            skip(r -> r != ' ');
                            start = inputPos;
                        }
                        break;
                    case '-':
                        matchDeclaration();
                        return curr = Token.DECLARATION;
                    case '#':
                        // vector#1cb5c415 {t:Type} # [ t ] = Vector t; (just ignore)
                        if (curr == Token.ID) {
                            skip(r -> r != '=');
                            start = inputPos + 1;
                            allowSpace = true;
                            break;
                        }

                        state &= ~COMPLETED;
                        t = Token.NAME;
                        break loop;
                    case ' ':
                        // Vector t;
                        if (allowSpace) {
                            break;
                        }
                        // ex: bytes = Bytes; (just ignore)
                        if (curr != Token.NAME) {
                            skipType0();
                            start = inputPos;
                            break;
                        }

                        t = Token.ID;
                        break loop;
                    case ';':
                        // verification
                        if (curr != Token.PARAMETERS_END && curr != Token.ID)
                            throw invalidToken(start, Token.PARAMETERS_END + " or " + Token.ID, curr);

                        state |= COMPLETED;
                        t = Token.TYPE_NAME;
                        break loop;
                    case ':':
                        // verification
                        if (curr != Token.ID)
                            throw invalidToken(start, Token.ID, curr);

                        t = Token.PARAMETERS_BEGIN;
                        next = Token.NAME;
                        state |= IN_PARAMS;
                        break loop;
                }
            } else {
                switch (c) {
                    case ':':
                        if (curr != Token.TYPE_NAME && curr != Token.ID)
                            throw invalidToken(start, Token.TYPE_NAME + " or " + Token.ID, curr);

                        t = Token.NAME;
                        break loop;
                    case ' ':
                        if (curr != Token.NAME)
                            throw invalidToken(start, Token.NAME, curr);

                        t = Token.TYPE_NAME;
                        break loop;
                }
            }
        }

        int len = inputPos - 1 - start;
        text.append(input, start, len);

        curr = t;
        return t;
    }

    public String asTextValue() {
        if (curr == Token.PARAMETERS_BEGIN || curr == Token.PARAMETERS_END)
            throw new IllegalStateException("Unable to get a text value for the token type: " + curr);
        if (curr == Token.DECLARATION)
            return kind.toString();
        return text.toString();
    }

    public Kind kind() {
        if (kind != null) {
            return kind;
        }
        return Kind.CONSTRUCTOR;
    }

    @Nullable
    private Token nextToken0(int start) throws IOException {
        text.append(input, start, inputEnd - start);

        if (!loadMore()) {
            handleEOF();
            return null;
        }

        boolean allowSpace = false;
        start = 0;
        Token t;
        char c;
        loop:
        while (true) {
            if (inputPos >= inputEnd) {
                text.append(input, 0, inputEnd);
                if (!loadMore()) {
                    handleEOF();
                    return null;
                }
            }

            c = input[inputPos++];
            if (c == '\n' || c == '\r') {
                inputRow++;
                throw createException(inputPos - 1, "Unexpected new line in the type declaration");
            }

            if ((state & IN_PARAMS) == 0) {
                switch (c) {
                    case '{':
                        if (curr == Token.ID) {
                            skip(r -> r != ' ');
                            start = inputPos;
                        }
                        break;
                    case '-':
                        matchDeclaration();
                        return curr = Token.DECLARATION;
                    case '#':
                        // vector#1cb5c415 {t:Type} # [ t ] = Vector t; (just ignore)
                        if (curr == Token.ID) {
                            skip(r -> r != '=');
                            start = inputPos;
                            allowSpace = true;
                            break;
                        }

                        state &= ~COMPLETED;
                        t = Token.NAME;
                        break loop;
                    case ' ':
                        // Vector t;
                        if (allowSpace) {
                            break;
                        }

                        // ex: bytes = Bytes; (just ignore)
                        if (curr != Token.NAME) {
                            skipType0();
                            start = inputPos;
                            continue;
                        }

                        t = Token.ID;
                        break loop;
                    case ';':
                        if (curr != Token.PARAMETERS_END && curr != Token.ID)
                            throw invalidToken(start, Token.PARAMETERS_END + " or " + Token.ID, curr);

                        t = Token.TYPE_NAME;
                        state |= COMPLETED;
                        break loop;
                    case ':':
                        if (curr != Token.ID)
                            throw invalidToken(start, Token.ID, curr);

                        t = Token.PARAMETERS_BEGIN;
                        next = Token.NAME;
                        state |= IN_PARAMS;
                        break loop;
                }
            } else {
                switch (c) {
                    case ':':
                        if (curr != Token.TYPE_NAME && curr != Token.ID)
                            throw invalidToken(start, Token.TYPE_NAME + " or " + Token.ID, curr);

                        t = Token.NAME;
                        break loop;
                    case ' ':
                        if (curr != Token.NAME)
                            throw invalidToken(start, Token.NAME, curr);

                        t = Token.TYPE_NAME;
                        break loop;
                }
            }
        }

        int len = inputPos - 1 - start;
        text.append(input, start, len);
        return t;
    }

    private void skip(CharPredicate pred) throws IOException {
        char c;
        do {
            if (inputPos >= inputEnd) {
                if (!loadMore()) {
                    handleEOF();
                    return;
                }
            }

            c = input[inputPos++];
            updateLocation(c);
        } while (pred.test(c));
    }

    private void skipComment() throws IOException {
        if (input[inputPos] == '/') {
            skip(c -> c != '\n');
        } else {
            throw createException(inputPos, "Unexpected char in comment begin '" + input[inputPos] + "'");
        }
    }

    private void handleEOF(String comment) throws IOException {
        if ((state & COMPLETED) == 0) {
            throw new TlParseException("Unexpected EOF at line: " + inputRow
                    + ", offset: " + inputProcessed + ": " + comment);
        }
    }

    private void handleEOF() throws IOException {
        if ((state & COMPLETED) == 0) {
            throw new TlParseException("Unexpected EOF at line: " + inputRow + ", offset: " + inputProcessed);
        }
    }

    private void matchDeclaration() throws IOException {
        int p = inputPos;
        if (p + 13 < inputEnd || p + 9 < inputEnd) {
            if (input[p] != '-' || input[++p] != '-') {
                throw createException(p, "Invalid declaration separator format, unexpected prefix: '-"
                        + input[p - 1] + input[p] + "'");
            }

            char c;
            while (true) { // find last '---'
                if (p >= inputEnd) { // failed to find suffix
                    break;
                }

                c = input[p++];
                if (c == '\n' || c == '\r') {
                    inputRow++;
                    throw createException(p - 1, "Unexpected new line in the type declaration");
                }

                if (p + 1 < inputEnd && c == '-' && input[p] == '-' && input[p + 1] == '-') {
                    break;
                }
            }

            int start = inputPos + 2;
            int len = p - 1 - start;
            String str = new String(input, start, len);
            inputPos = p + 2;
            switch (str) {
                case "functions":
                    kind = Kind.METHOD;
                    break;
                case "types":
                    kind = Kind.CONSTRUCTOR;
                    break;
                default:
                    throw createException(start, "Unexpected declaration type: '" + str + "'");
            }
        } else {
            matchDeclaration0();
        }
    }

    // implementation for end-of-input, eof situations
    private void matchDeclaration0() throws IOException {
        if (!loadMore()) {
            handleEOF();
            return;
        } else if (inputPos + 13 >= inputEnd || inputPos + 9 >= inputEnd) {
            handleEOF("when expected declaration separator");
            return;
        }

        if (input[inputPos] != '-' || input[++inputPos] != '-') {
            throw createException(inputPos, "Invalid declaration separator format, unexpected prefix: '-"
                    + input[inputPos - 1] + input[inputPos] + "'");
        }

        int start = inputPos + 1;

        char c;
        while (true) {
            if (inputPos >= inputEnd) {
                text.append(input, start, inputEnd - start);
                start = 0;
                if (!loadMore()) {
                    handleEOF("when expected suffix of the declaration separator");
                    break;
                }
            }

            c = input[inputPos++];
            if (c == '\n' || c == '\r') {
                inputRow++;
                throw createException(inputPos - 1, "Unexpected new line in the type declaration");
            }

            // find last '---'
            if (inputPos + 1 < inputEnd && c == '-' && input[inputPos] == '-' && input[inputPos + 1] == '-') {
                break;
            }
        }

        int len = inputPos - 1 - start;
        text.append(input, start, len);
        inputPos += 2;

        switch (text.toString()) {
            case "functions":
                kind = Kind.METHOD;
                break;
            case "types":
                kind = Kind.CONSTRUCTOR;
                break;
            default:
                throw createException(start, "Unexpected declaration type: '" + text + "'");
        }
    }

    private boolean updateLocation(char c) {
        if (c == '\n' || c == '\r') {
            inputRow++;
            inputColumn = 0;
            inputColumnStart = inputPos;
            return true;
        }
        return false;
    }

    private boolean loadMore() throws IOException {
        int c = reader.read(input);
        if (c == -1) {
            return false;
        }

        inputProcessed += inputEnd;
        inputColumn += inputEnd - inputColumnStart;
        inputColumnStart = 0;

        inputPos = 0;
        inputEnd = c;
        return true;
    }

    private TlParseException invalidToken(int start, Object expecting, Token actual) {
        return createException(start, "Unrecognized token " + actual + ", was expecting " + expecting);
    }

    private TlParseException createException(int start, String text) {
        inputProcessed += start;
        inputColumn += start - inputColumnStart;
        int column = inputColumn + 1; // 1-based
        return new TlParseException(text + " at line: "
                + inputRow + ", position: " + column + ", offset: " + inputProcessed);
    }

    private void skipType0() throws IOException {
        skip(c -> c != ';');

        char c;
        wsLoop:
        while (true) {
            if (inputPos >= inputEnd) {
                if (!loadMore()) {
                    handleEOF();
                    return;
                }
            }

            c = input[inputPos++];
            switch (c) {
                case '\n':
                case '\r':
                    inputRow++;
                    inputColumn = 0;
                    inputColumnStart = inputPos;
                case ' ':
                    break;
                default:
                    break wsLoop;
            }
        }

        inputPos--;
    }

    private interface CharPredicate {

         boolean test(char c);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    public enum Token {
        DECLARATION, // can be ignored
        ID,
        NAME,
        TYPE_NAME,
        PARAMETERS_BEGIN,
        PARAMETERS_END
    }
}
