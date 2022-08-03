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
        String name = node.get(kind == Type.Kind.CONSTRUCTOR ? "predicate" : "method").asText();
        String id = node.get("id").asText();
        List<TlTrees.Parameter> params = ctxt.readTreeAsValue(node.get("params"), ctxt.getTypeFactory()
                .constructCollectionType(List.class, Parameter.class));
        String type = node.get("type").asText();

        return ImmutableTlTrees.Type.builder()
                .kind(kind)
                .name(name)
                .id(id)
                .parameters(params)
                .type(type)
                .build();
    }
}
