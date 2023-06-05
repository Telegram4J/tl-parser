package telegram4j.tl.json;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializerBase;
import com.fasterxml.jackson.databind.util.ClassUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import telegram4j.tl.TlInfo;
import telegram4j.tl.api.TlObject;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
        var moduleDesc = TlModule.class.getModule().getDescriptor();
        if (moduleDesc == null) {
            return new Version(0, 1, 2, "SNAPSHOT", "com.telegram4j", "tl-parser");
        }

        String[] versionAndInfo = moduleDesc.rawVersion().orElseThrow().split("-");
        String[] version = versionAndInfo[0].split("\\.");
        String snapshot = versionAndInfo.length == 2 ? versionAndInfo[1] : null;

        return new Version(Integer.parseInt(version[0]), Integer.parseInt(version[1]),
                Integer.parseInt(version[2]), snapshot, "com.telegram4j", "tl-parser");
    }

    @Override
    public void setupModule(SetupContext context) {
        context.addSerializers(new Serializers.Base() {
            @Override
            public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
                if (type.isTypeOrSubTypeOf(ByteBuf.class)) {
                    return ByteBufSerializer.instance;
                }
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
                if (beanDesc.getType().isTypeOrSubTypeOf(ByteBuf.class)) {
                    return ByteBufDeserializer.instance;
                }
                if (!beanDesc.getType().isTypeOrSubTypeOf(TlObject.class)) {
                    return deserializer;
                }
                return new TlJsonDeserializer(beanDesc);
            }
        });
    }

    static class ByteBufDeserializer extends FromStringDeserializer<ByteBuf> {

        private static final ByteBufDeserializer instance = new ByteBufDeserializer();

        private ByteBufDeserializer() {
            super(ByteBuf.class);
        }

        @Override
        protected ByteBuf _deserialize(String value, DeserializationContext ctxt) {
            return Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(value));
        }
    }

    static class ByteBufSerializer extends ToStringSerializerBase {

        private static final ByteBufSerializer instance = new ByteBufSerializer();

        private ByteBufSerializer() {
            super(ByteBuf.class);
        }

        @Override
        public boolean isEmpty(SerializerProvider prov, Object value) {
            return !((ByteBuf) value).isReadable();
        }

        @Override
        public String valueToString(Object value) {
            return ByteBufUtil.hexDump((ByteBuf) value);
        }
    }

    static class TlJsonDeserializer extends JsonDeserializer<TlObject> {

        static final Class<?>[] OF_METHOD_PARAMS = {int.class};

        private final BeanDescription beanDesc;

        TlJsonDeserializer(BeanDescription beanDesc) {
            this.beanDesc = beanDesc;
        }

        @Override
        public TlObject deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.START_OBJECT) p.nextToken();
            if (!"identifier".equals(p.currentName()) || p.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                return ctxt.reportInputMismatch(this, "Expected 'identifier' as first, but given '%s' with type %s",
                        p.currentName(), p.currentToken());
            }

            int id = p.getIntValue();
            JavaType type = ctxt.constructType(TlInfo.typeOf(id));
            if (beanDesc.getType().isEnumType() || type.isEnumType()) {
                if (!type.isTypeOrSubTypeOf(beanDesc.getType().getRawClass())) {
                    throw ctxt.invalidTypeIdException(beanDesc.getType(), ClassUtil.getTypeDescription(type), "Not a subtype");
                }

                try {
                    Method ofMethod = type.getRawClass().getMethod("of", OF_METHOD_PARAMS);

                    return (TlObject) ofMethod.invoke(null, id);
                } catch (Throwable e) {
                    return wrapInstantiationProblem(e, ctxt);
                }
            }

            Object builder;
            try {
                Method builderMethod = type.getRawClass().getMethod("builder");
                builder = builderMethod.invoke(null);
            } catch (NoSuchMethodException e) {
                try {
                    Method m = type.getRawClass().getMethod("instance");
                    return (TlObject) m.invoke(null);
                } catch (Exception e1) {
                    return wrapInstantiationProblem(e, ctxt);
                }
            } catch (Exception e) {
                return wrapInstantiationProblem(e, ctxt);
            }

            var methods = Arrays.stream(builder.getClass().getMethods())
                    .filter(m -> m.isAnnotationPresent(JsonSetter.class) ||
                            m.getName().equals("build") && m.getParameterCount() == 0)
                    .collect(Collectors.toMap(v -> {
                        JsonSetter ann = v.getAnnotation(JsonSetter.class);
                        return ann != null && !ann.value().isEmpty() ? ann.value() : v.getName();
                    }, Function.identity()));

            if (p.nextToken() == JsonToken.FIELD_NAME) {
                String propName = p.currentName();
                do {
                    p.nextToken();
                    Method prop = methods.get(propName);

                    if (prop != null) { // normal case
                        JavaType c = ctxt.constructType(prop.getGenericParameterTypes()[0]);
                        if (p.hasToken(JsonToken.VALUE_NUMBER_INT) && c.isTypeOrSubTypeOf(TlObject.class)) { // simplified serialization
                            int propId = p.getIntValue();
                            JavaType act = ctxt.constructSpecializedType(c, TlInfo.typeOf(propId));
                            if (!act.isEnumType()) {
                                try {
                                    Method instanceMethod = act.getRawClass().getMethod("instance");
                                    Object o = instanceMethod.invoke(null);
                                    prop.invoke(builder, o);
                                } catch (Exception e) {
                                    wrapAndThrow(e, builder, propName, ctxt);
                                }
                            } else {
                                try {
                                    Method ofMethod = act.getRawClass().getMethod("of", OF_METHOD_PARAMS);

                                    Object o = ofMethod.invoke(null, propId);
                                    prop.invoke(builder, o);
                                } catch (Exception e) {
                                    wrapAndThrow(e, builder, propName, ctxt);
                                }
                            }

                        } else {
                            var deser = ctxt.findRootValueDeserializer(c);
                            Object o = deser.deserialize(p, ctxt);
                            try {
                                prop.invoke(builder, o);
                            } catch (Exception e) {
                                wrapAndThrow(e, builder, propName, ctxt);
                            }
                        }

                        continue;
                    }
                    handleUnknownProperty(p, ctxt, builder, propName);
                } while ((propName = p.nextFieldName()) != null);
            }

            try {
                Method m = methods.get("build");
                return (TlObject) m.invoke(builder);
            } catch (Exception e) {
                return wrapInstantiationProblem(e, ctxt);
            }
        }

        // methods below taken from the BeanDeserializerBase

        public void wrapAndThrow(Throwable t, Object bean, String fieldName, DeserializationContext ctxt) throws IOException {
            // Need to add reference information
            throw JsonMappingException.wrapWithPath(throwOrReturnThrowable(t, ctxt), bean, fieldName);
        }

        private Throwable throwOrReturnThrowable(Throwable t, DeserializationContext ctxt) throws IOException {
            /* 05-Mar-2009, tatu: But one nasty edge is when we get
             *   StackOverflow: usually due to infinite loop. But that
             *   often gets hidden within an InvocationTargetException...
             */
            while (t instanceof InvocationTargetException && t.getCause() != null) {
                t = t.getCause();
            }
            // Errors to be passed as is
            ClassUtil.throwIfError(t);
            boolean wrap = ctxt == null || ctxt.isEnabled(DeserializationFeature.WRAP_EXCEPTIONS);
            // Ditto for IOExceptions; except we may want to wrap JSON exceptions
            if (t instanceof IOException) {
                if (!wrap || !(t instanceof JacksonException)) {
                    throw (IOException) t;
                }
            } else if (!wrap) { // [JACKSON-407] -- allow disabling wrapping for unchecked exceptions
                ClassUtil.throwIfRTE(t);
            }
            return t;
        }

        protected TlObject wrapInstantiationProblem(Throwable t, DeserializationContext ctxt) throws IOException {
            while (t instanceof InvocationTargetException && t.getCause() != null) {
                t = t.getCause();
            }
            // Errors and "plain" IOExceptions to be passed as is
            ClassUtil.throwIfError(t);
            if (t instanceof IOException e) {
                // Since we have no more information to add, let's not actually wrap..
                throw e;
            }
            if (ctxt == null) { // only to please LGTM...
                throw new IllegalArgumentException(t.getMessage(), t);
            }
            if (!ctxt.isEnabled(DeserializationFeature.WRAP_EXCEPTIONS)) {
                ClassUtil.throwIfRTE(t);
            }
            return (TlObject) ctxt.handleInstantiationProblem(beanDesc.getType().getRawClass(), null, t);
        }

        protected void handleUnknownProperty(JsonParser p, DeserializationContext ctxt,
                                             Object instanceOrClass, String propName) throws IOException {
            if (instanceOrClass == null) {
                instanceOrClass = handledType();
            }
            // Maybe we have configured handler(s) to take care of it?
            if (ctxt.handleUnknownProperty(p, this, instanceOrClass, propName)) {
                return;
            }
            /* But if we do get this far, need to skip whatever value we
             * are pointing to now (although handler is likely to have done that already)
             */
            p.skipChildren();
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

                JavaType act = p.getPrimaryType().isTypeOrSubTypeOf(TlObject.class)
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
