module telegram4j.tl.parser {
    requires com.fasterxml.jackson.databind;
    requires reactor.core;
    requires reactor.netty.http;
    requires reactor.extra;
    requires reactor.netty.core;
    requires org.reactivestreams;
    requires io.netty.buffer;
    requires java.compiler;

    requires telegram4j.tl.api;

    requires static org.immutables.value;

    exports telegram4j.tl.generator;
}
