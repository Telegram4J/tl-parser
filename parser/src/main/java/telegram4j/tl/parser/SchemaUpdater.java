package telegram4j.tl.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.netty.http.client.HttpClient;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

class SchemaUpdater {

    static ObjectMapper mapper = new ObjectMapper()
            .setDefaultPrettyPrinter(new CorrectPrettyPrinter());

    static String pathPrefix = "./src/main/resources/";
    static List<Tuple2<String, String>> schemes = List.of(
            Tuples.of("https://raw.githubusercontent.com/telegramdesktop/tdesktop/dev/Telegram/Resources/tl/api.tl", "telegram4j/tl/api"));

    public static void main(String[] args) {
        HttpClient client = HttpClient.create();

        Flux.fromIterable(schemes)
                .flatMap(TupleUtils.function((url, filename) -> client.get().uri(url)
                        .responseSingle((res, buf) -> buf.asByteArray())
                        .flatMap(buf -> handleScheme(buf, filename))
                        .publishOn(Schedulers.boundedElastic())))
                .then()
                .block();
    }

    private static Mono<?> handleScheme(byte[] buf, String filename) {
        return Mono.fromCallable(() -> {
            try (var p = new TlParser(new InputStreamReader(
                    new ByteArrayInputStream(buf), StandardCharsets.UTF_8));
                 var gen = mapper.writerWithDefaultPrettyPrinter()
                         .createGenerator(Files.newBufferedWriter(Path.of(pathPrefix, filename + ".json")))) {

                boolean inParams = false;
                TlParser.Token t;

                gen.writeStartObject();
                gen.writeArrayFieldStart("constructors");

                while ((t = p.nextToken()) != null) {
                    switch (t) {
                        case DECLARATION:
                            if (p.kind() != Kind.METHOD)
                                throw new IllegalStateException();

                            gen.writeEndArray();
                            gen.writeArrayFieldStart("methods");
                            break;
                        case ID:
                            gen.writeStringField("id", p.asTextValue());
                            break;
                        case NAME:
                            gen.writeStartObject();

                            if (inParams) {
                                gen.writeStringField("name", p.asTextValue());
                            } else {
                                gen.writeStringField(p.kind() == TlTrees.Type.Kind.CONSTRUCTOR
                                        ? "predicate" : "method", p.asTextValue());
                            }
                            break;
                        case TYPE_NAME:
                            gen.writeStringField("type", p.asTextValue());
                            gen.writeEndObject();
                            break;
                        case PARAMETERS_END:
                            gen.writeEndArray();
                            inParams = false;
                            break;
                        case PARAMETERS_BEGIN:
                            gen.writeArrayFieldStart("params");
                            inParams = true;
                            break;
                    }
                }
            }
            return null;
        })
        .and(Mono.fromCallable(() -> Files.write(Path.of(pathPrefix, filename + ".tl"),
                buf, StandardOpenOption.CREATE)));
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
