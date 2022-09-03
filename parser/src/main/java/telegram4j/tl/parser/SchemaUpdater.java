package telegram4j.tl.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.netty.http.client.HttpClient;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

class SchemaUpdater {

    static PrettyPrinter correctPrettyPrinter = new CorrectPrettyPrinter();
    static ObjectMapper mapper = new ObjectMapper()
            .setDefaultPrettyPrinter(correctPrettyPrinter);

    static String pathPrefix = "./parser/src/main/resources/";
    static List<Tuple2<String, String>> schemes = List.of(
            Tuples.of("https://core.telegram.org/schema/json", "api.json"),
            Tuples.of("https://core.telegram.org/schema/mtproto-json", "mtproto.json"),
            Tuples.of("https://raw.githubusercontent.com/tdlib/td/master/td/generate/scheme/telegram_api.tl", "api.tl"));

    public static void main(String[] args) throws Throwable {
        if (args.length >= 3 && args[0].equals("-convert")) {
            // -convert api.tl api.json
            // and optional -verify api-orig.json
            try (var p = new TlParser(Files.newBufferedReader(Path.of(pathPrefix, args[1])))) {
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

                var computed = scheme.build();

                if (args.length == 5 && args[3].equals("-verify")) {
                    var expected = mapper.readValue(new File(pathPrefix + args[4]), TlTrees.Scheme.class);

                    var cc = new HashSet<>(computed.constructors());
                    var cm = new HashSet<>(computed.methods());

                    var ec = new HashSet<>(expected.constructors());
                    var em = new HashSet<>(expected.methods());

                    if (!cc.equals(ec)) {
                        throw new IllegalStateException("Failed to compare constructors definition. expected: "
                                + ec.size() + ", computed: " + cc.size());
                    }
                    if (!cm.equals(em)) {
                        throw new IllegalStateException("Failed to compare methods definition. expected: "
                                + em.size() + ", computed: " + em.size());
                    }
                }

                mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(new File(pathPrefix + args[2]), computed);
            }
            return;
        }

        HttpClient client = HttpClient.create();

        Flux.fromIterable(schemes)
                .flatMap(TupleUtils.function((url, filename) -> client.get().uri(url)
                        .responseSingle((res, buf) -> buf.asString())
                        .flatMap(str -> filename.endsWith(".tl")
                                ? handleTlScheme(str, filename)
                                : handleJsonScheme(str, filename))))
                .then()
                .block();
    }

    private static Mono<Void> handleTlScheme(String str, String filename) {
        return Mono.fromCallable(() -> Files.writeString(Path.of(pathPrefix, filename), str))
                .then();
    }

    static Mono<Void> handleJsonScheme(String str, String filename) {
        return Mono.fromCallable(() -> mapper.readTree(str))
                .doOnNext(SchemaUpdater::applyModification)
                .flatMap(tree -> Mono.fromCallable(() -> {
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValue(new File(pathPrefix + filename), tree);
                    return null;
                }))
                .then();
    }

    static void applyModification(JsonNode node) {
        for (JsonNode arrays : node) {
            for (JsonNode array : arrays) {
                ObjectNode obj = (ObjectNode) array;

                // convert decimal id to hex format
                String hexedId = Integer.toHexString(obj.get("id").asInt());
                obj.put("id", hexedId);
            }
        }
    }

    static class CorrectPrettyPrinter extends DefaultPrettyPrinter {

        public CorrectPrettyPrinter() {
            _arrayIndenter = DefaultIndenter.SYSTEM_LINEFEED_INSTANCE;
            withSpacesInObjectEntries();
            _objectFieldValueSeparatorWithSpaces = _separators.getObjectFieldValueSeparator() + " ";
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new CorrectPrettyPrinter();
        }

        @Override
        public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
            if (!_arrayIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfValues > 0) {
                _arrayIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw(']');
        }
    }
}
