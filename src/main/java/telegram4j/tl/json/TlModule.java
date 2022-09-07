package telegram4j.tl.json;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.ser.Serializers;
import telegram4j.tl.TlInfo;
import telegram4j.tl.api.TlObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TlModule extends Module {

    @Override
    public String getModuleName() {
        return "TlModule";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 2, null, null, null);
    }

    @Override
    public void setupModule(SetupContext context) {
        context.addSerializers(new Serializers.Base() {
            @Override
            public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
                if (!type.isTypeOrSubTypeOf(TlObject.class)) {
                    return null;
                }

                return new TlJsonSerializer(beanDesc);
            }
        });
        context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyEnumDeserializer(DeserializationConfig config, JavaType type,
                                                              BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                if (!beanDesc.getType().isTypeOrSubTypeOf(TlObject.class)) {
                    return deserializer;
                }
                return new TlJsonDeserializer(beanDesc);
            }

            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
                                                          JsonDeserializer<?> deserializer) {
                if (!beanDesc.getType().isTypeOrSubTypeOf(TlObject.class)) {
                    return deserializer;
                }
                return new TlJsonDeserializer(beanDesc);
            }
        });
    }

    static class TlJsonDeserializer extends JsonDeserializer<TlObject> {

        private final BeanDescription beanDesc;

        TlJsonDeserializer(BeanDescription beanDesc) {
            this.beanDesc = beanDesc;
        }

        @Override
        public TlObject deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.START_OBJECT) p.nextToken();
            if (!"identifier".equals(p.currentName()) || p.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                return ctxt.reportInputMismatch(this, "expected 'identifier' as first, but given '%s' with type %s",
                        p.currentName(), p.currentToken());
            }

            int id = p.getIntValue();
            p.nextToken();

            JavaType type = ctxt.constructType(TlInfo.typeOf(id));
            if (beanDesc.getType().isEnumType() || type.isEnumType()) {
                if (!type.isTypeOrSubTypeOf(beanDesc.getType().getRawClass())) {
                    throw new IllegalStateException(); // TODO:
                }

                try {
                    Method ofMethod = type.getRawClass().getMethod("of", int.class);

                    return (TlObject) ofMethod.invoke(null, id);
                } catch (Exception e) {
                    throw new IllegalStateException(e); // TODO
                }
            }

            Object builder;
            try {
                Method builderMethod = type.getRawClass().getMethod("builder");
                builder = builderMethod.invoke(null);
            } catch (Exception e) {
                try {
                    Method m = type.getRawClass().getMethod("instance");
                    return (TlObject) m.invoke(null);
                } catch (Exception e1) {
                    throw new IllegalArgumentException(e); // TODO:
                }
            }

            var methods = Arrays.stream(builder.getClass().getMethods())
                    .filter(m -> m.isAnnotationPresent(JsonSetter.class) ||
                            m.getName().equals("build") && m.getParameterCount() == 0)
                    .collect(Collectors.toMap(v -> {
                        JsonSetter ann = v.getAnnotation(JsonSetter.class);
                        return ann != null && !ann.value().isEmpty() ? ann.value() : v.getName();
                    }, Function.identity()));

            if (p.hasToken(JsonToken.FIELD_NAME)) {
                String propName = p.currentName();
                do {
                    p.nextToken();
                    Method prop = methods.get(propName);

                    if (prop != null) { // normal case
                        JavaType c = ctxt.constructType(prop.getGenericParameterTypes()[0]);
                        if (p.hasToken(JsonToken.VALUE_NUMBER_INT) && c.isTypeOrSubTypeOf(TlObject.class)) {
                            int propId = p.getIntValue();
                            JavaType act = ctxt.constructSpecializedType(c, TlInfo.typeOf(propId));
                            if (!act.isEnumType()) {
                                try {
                                    Method instanceMethod = act.getRawClass().getMethod("instance");
                                    Object o = instanceMethod.invoke(null);
                                    prop.invoke(builder, o);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e); // TODO
                                }
                            } else {
                                try {
                                    Method ofMethod = act.getRawClass().getMethod("of", int.class);

                                    Object o = ofMethod.invoke(null, propId);
                                    prop.invoke(builder, o);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e); // TODO
                                }
                            }

                        } else {
                            var deser = ctxt.findRootValueDeserializer(c);
                            Object o = deser.deserialize(p, ctxt);
                            try {
                                prop.invoke(builder, o);
                            } catch (Exception e) {
                                throw new IllegalStateException(e); // TODO
                            }
                        }

                        continue;
                    }
                    throw new IllegalStateException(); // TODO
                } while ((propName = p.nextFieldName()) != null);
            }

            try {
                Method m = methods.get("build");
                return (TlObject) m.invoke(builder);
            } catch (Exception e) {
                throw new IllegalStateException(e); // TODO:
            }
        }
    }

    static class TlJsonSerializer extends JsonSerializer<TlObject> {

        private final BeanDescription beanDesc;

        protected TlJsonSerializer(BeanDescription beanDesc) {
            this.beanDesc = beanDesc;
        }

        @Override
        public void serialize(TlObject value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeNumberField("identifier", value.identifier());

            serializeFields(value, gen, provider);

            gen.writeEndObject();
        }

        protected void serializeFields(TlObject bean, JsonGenerator gen, SerializerProvider provider) throws IOException {
            for (BeanPropertyDefinition p : beanDesc.findProperties()) {
                AnnotatedMember ac = p.getAccessor();
                if (ac == null) {
                    continue;
                }

                Object o = ac.getValue(bean);
                if (o == null) {
                    continue;
                }

                JavaType act = p.getPrimaryType().hasGenericTypes()
                        ? provider.constructSpecializedType(p.getPrimaryType(), o.getClass())
                        : p.getPrimaryType();

                gen.writeFieldName(p.getName());
                if (p.getPrimaryType().isTypeOrSubTypeOf(TlObject.class) &&
                        (p.getPrimaryType().isEnumType() || provider.getConfig()
                                .introspect(act).findProperties().isEmpty())) {
                    TlObject c = (TlObject) o;
                    gen.writeNumber(c.identifier());
                } else {
                    var ser = provider.findValueSerializer(act);
                    ser.serialize(o, gen, provider);
                }
            }
        }
    }
}
