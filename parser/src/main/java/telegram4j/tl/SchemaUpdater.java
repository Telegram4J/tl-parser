package telegram4j.tl;

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

import java.io.File;
import java.io.IOException;
import java.util.List;

class SchemaUpdater {

    static PrettyPrinter correctPrettyPrinter = new CorrectPrettyPrinter();

    static String pathPrefix = "./parser/src/main/resources/";
    static List<Tuple2<String, String>> schemes = List.of(
            Tuples.of("https://core.telegram.org/schema/json", "api.json"),
            Tuples.of("https://core.telegram.org/schema/mtproto-json", "mtproto.json"));

    public static void main(String[] args) {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.create();

        Flux.fromIterable(schemes)
                .flatMap(TupleUtils.function((url, filename) -> client.get().uri(url)
                        .responseSingle((res, buf) -> buf.asInputStream())
                        .flatMap(in -> Mono.fromCallable(() -> mapper.readTree(in)))
                        .doOnNext(SchemaUpdater::applyModification)
                        .flatMap(tree -> Mono.fromCallable(() -> mapper.writer(correctPrettyPrinter)
                                .writeValues(new File(pathPrefix + filename))
                                .write(tree)))))
                .then()
                .block();
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
