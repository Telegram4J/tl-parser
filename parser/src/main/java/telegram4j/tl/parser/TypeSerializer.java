package telegram4j.tl.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import telegram4j.tl.parser.TlTrees.Type;

import java.io.IOException;

public class TypeSerializer extends StdSerializer<Type> {

    protected TypeSerializer() {
        super(Type.class);
    }

    @Override
    public void serialize(Type value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("id", value.id());
        gen.writeStringField(value.kind() == Type.Kind.CONSTRUCTOR ? "predicate" : "method", value.name());
        gen.writeObjectField("params", value.parameters());
        gen.writeStringField("type", value.type());
        gen.writeEndObject();
    }
}
