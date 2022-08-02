package telegram4j.tl.parser;

import java.io.IOException;

public class TlParseException extends IOException {

    public TlParseException() {
    }

    public TlParseException(String message) {
        super(message);
    }
}
