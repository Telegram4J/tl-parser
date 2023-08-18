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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import telegram4j.tl.parser.TlTrees.Parameter;
import telegram4j.tl.parser.TlTrees.Type;

import java.io.IOException;
import java.util.List;

class TypeDeserializer extends StdDeserializer<Type> {

    protected TypeDeserializer() {
        super(Type.class);
    }

    @Override
    public Type deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        Type.Kind kind = node.has("predicate") ? Type.Kind.CONSTRUCTOR : Type.Kind.METHOD;
        String id = node.required("id").asText();
        String name = node.required(kind == Type.Kind.CONSTRUCTOR ? "predicate" : "method").asText();
        List<TlTrees.Parameter> params = node.has("params") ?
                ctxt.readTreeAsValue(node.get("params"), ctxt.getTypeFactory()
                        .constructCollectionType(List.class, Parameter.class)) : List.of();
        String type = node.required("type").asText();

        return ImmutableTlTrees.Type.of(kind, id, name, params, type);
    }
}
