package telegram4j.tl.parser;

import org.junit.jupiter.api.Test;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import java.io.*;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class TlParserTest {

    TlTrees.Scheme readTl(String resource) {
        Reader in;
        if (resource.startsWith("resources://")) {
            InputStream is = getClass().getResourceAsStream(resource);
            Objects.requireNonNull(is, "Unable to find resource with name: " + resource);
            in = new InputStreamReader(is);
        } else {
            in = new StringReader(resource);
        }

        try (TlParser p = new TlParser(in)) {
            var scheme = ImmutableTlTrees.Scheme.builder();
            var type = ImmutableTlTrees.Type.builder();
            var param = ImmutableTlTrees.Parameter.builder();

            boolean inParams = false;
            TlParser.Token t;
            while ((t = p.nextToken()) != null) {
                switch (t) {
                    case ID:
                        type.id(p.asTextValue());
                        break;
                    case NAME:
                        if (inParams) {
                            param.name(p.asTextValue());
                        } else {
                            type.kind(p.kind());
                            type.name(p.asTextValue());
                        }
                        break;
                    case TYPE_NAME:
                        if (inParams) {
                            param.type(p.asTextValue());

                            type.addParameter(param.build());
                            param = ImmutableTlTrees.Parameter.builder();
                        } else {
                            type.type(p.asTextValue());
                            if (p.kind() == Kind.CONSTRUCTOR) {
                                scheme.addConstructor(type.build());
                            } else {
                                scheme.addMethods(type.build());
                            }

                            type = ImmutableTlTrees.Type.builder();
                        }
                        break;
                    case PARAMETERS_END:
                    case PARAMETERS_BEGIN:
                        inParams = t == TlParser.Token.PARAMETERS_BEGIN;
                        break;
                }
            }

            return scheme.build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void checkFormat() {
        var scheme = readTl(
                "string ? = String;\n" +
                        "bytes = Bytes;\n" +
                        "true#3fedd339 = True;\n" +
                        "vector#1cb5c415 {t:Type} # [ t ] = Vector t;\n" +
                        "error#c4b9f9bb code:int text:string = Error;\n" +
                        "---functions---\n" +
                        "invokeAfterMsg#cb9f372d {X:Type} msg_id:long query:!X = X;\n" +
                        "folders.deleteFolder#1c295881 folder_id:int = Updates;\n" +
                        "---types---\n" +
                        "a#1 = b;");

        var exp = ImmutableTlTrees.Scheme.builder()
                .addConstructor(ImmutableTlTrees.Type.builder()
                        .kind(Kind.CONSTRUCTOR)
                        .name("true")
                        .id("3fedd339")
                        .type("True")
                        .build())
                .addConstructor(ImmutableTlTrees.Type.builder()
                        .kind(Kind.CONSTRUCTOR)
                        .name("vector")
                        .id("1cb5c415")
                        .type("Vector t")
                        .build())
                .addConstructor(ImmutableTlTrees.Type.builder()
                        .kind(Kind.CONSTRUCTOR)
                        .name("error")
                        .id("c4b9f9bb")
                        .addParameter(ImmutableTlTrees.Parameter.builder()
                                .name("code")
                                .type("int")
                                .build())
                        .addParameter(ImmutableTlTrees.Parameter.builder()
                                .name("text")
                                .type("string")
                                .build())
                        .type("Error")
                        .build())
                .addConstructor(ImmutableTlTrees.Type.builder()
                        .kind(Kind.CONSTRUCTOR)
                        .name("a")
                        .id("1")
                        .type("b")
                        .build())
                .addMethod(ImmutableTlTrees.Type.builder()
                        .kind(Kind.METHOD)
                        .name("invokeAfterMsg")
                        .id("cb9f372d")
                        .addParameter(ImmutableTlTrees.Parameter.builder()
                                .name("msg_id")
                                .type("long")
                                .build())
                        .addParameter(ImmutableTlTrees.Parameter.builder()
                                .name("query")
                                .type("!X")
                                .build())
                        .type("X")
                        .build())
                .addMethod(ImmutableTlTrees.Type.builder()
                        .kind(Kind.METHOD)
                        .name("folders.deleteFolder")
                        .id("1c295881")
                        .addParameter(ImmutableTlTrees.Parameter.builder()
                                .name("folder_id")
                                .type("int")
                                .build())
                        .type("Updates")
                        .build())
                .build();

        assertEquals(exp, scheme);
    }
}
