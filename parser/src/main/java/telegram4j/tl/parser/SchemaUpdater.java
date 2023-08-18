/*
 * Copyright 2023 Telegram4J
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package telegram4j.tl.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

class SchemaUpdater {

    static ObjectMapper mapper = new ObjectMapper()
            .setDefaultPrettyPrinter(new CorrectPrettyPrinter());

    static final String pathPrefix = "./src/main/resources/";
    static final String baseUri = "https://raw.githubusercontent.com/telegramdesktop/tdesktop/dev/Telegram/SourceFiles/mtproto/scheme/";
    static final String api = baseUri + "api.tl";
    static final String layer = baseUri + "layer.tl";

    static final Pattern LAYER_PATTERN = Pattern.compile(".*// LAYER (\\d+).*");

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        var apiSchemeResponse = client.send(HttpRequest.newBuilder().GET().uri(URI.create(api)).build(),
                HttpResponse.BodyHandlers.ofString());
        var layerSchemeResponse = client.send(HttpRequest.newBuilder().GET().uri(URI.create(layer)).build(),
                HttpResponse.BodyHandlers.ofString());
        var matcher = LAYER_PATTERN.matcher(layerSchemeResponse.body());
        if (!matcher.find()) {
            throw new IllegalArgumentException("No '// LAYER N' notation found in schema");
        }
        String version = matcher.group(1);
        handleScheme(apiSchemeResponse.body(), version, "api");
    }

    private static void handleScheme(String data, String version, String filename) throws IOException {
        try (var p = new TlParser(new StringReader(data));
             var gen = mapper.writerWithDefaultPrettyPrinter()
                     .createGenerator(Files.newBufferedWriter(Path.of(pathPrefix, filename + ".json")))) {

            boolean inParams = false;
            TlParser.Token t;

            gen.writeStartObject();
            gen.writeStringField("version", version);
            gen.writeArrayFieldStart("constructors");

            while ((t = p.nextToken()) != null) {
                switch (t) {
                    case DECLARATION -> {
                        if (p.kind() != Kind.METHOD)
                            throw new IllegalStateException();
                        gen.writeEndArray();
                        gen.writeArrayFieldStart("methods");
                    }
                    case ID -> gen.writeStringField("id", p.asTextValue());
                    case NAME -> {
                        gen.writeStartObject();
                        if (inParams) {
                            gen.writeStringField("name", p.asTextValue());
                        } else {
                            gen.writeStringField(p.kind() == Kind.CONSTRUCTOR
                                    ? "predicate" : "method", p.asTextValue());
                        }
                    }
                    case TYPE_NAME -> {
                        gen.writeStringField("type", p.asTextValue());
                        gen.writeEndObject();
                    }
                    case PARAMETERS_END -> {
                        gen.writeEndArray();
                        inParams = false;
                    }
                    case PARAMETERS_BEGIN -> {
                        gen.writeArrayFieldStart("params");
                        inParams = true;
                    }
                }
            }
        }

        Files.writeString(Path.of(pathPrefix, filename + ".tl"), data, StandardOpenOption.CREATE);
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
