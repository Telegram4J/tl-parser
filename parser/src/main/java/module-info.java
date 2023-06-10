module telegram4j.tl.parser {
    requires com.fasterxml.jackson.databind;
    requires reactor.core;
    requires io.netty.buffer;

    requires java.compiler;
    requires java.net.http;

    requires static org.immutables.value;

    exports telegram4j.tl.generator;
}
