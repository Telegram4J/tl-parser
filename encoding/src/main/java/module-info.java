module telegram4j.tl.encoding {
    requires io.netty.buffer;
    requires reactor.core;

    requires transitive org.immutables.encode;

    requires telegram4j.tl.api;

    exports telegram4j.tl.encoding;
}
