package telegram4j.tl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.netty.buffer.*;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.stream.StreamSupport;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static telegram4j.tl.TlPrimitives.*;

/** Intrinsics serialization methods used in the scheme parser. */
public final class TlSerialUtil {

    private TlSerialUtil() {
    }

    public static ByteBuf compressGzip(ByteBufAllocator allocator, int level, ByteBuf buf) {
        ByteBufOutputStream bufOut = new ByteBufOutputStream(allocator.buffer(buf.readableBytes()));
        try (DeflaterOutputStream out = new CompressibleGZIPOutputStream(bufOut, level)) {
            out.write(ByteBufUtil.getBytes(buf));
            out.finish();
            buf.release();
            return bufOut.buffer();
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
    }

    public static ByteBuf compressGzip(ByteBufAllocator allocator, int level, TlObject object) {
        return compressGzip(allocator, level, TlSerializer.serialize(allocator, object));
    }

    public static <T> T decompressGzip(ByteBuf packed) {
        ByteBuf result = packed.alloc().buffer(packed.readableBytes());
        try (GZIPInputStream in = new GZIPInputStream(new ByteBufInputStream(packed))) {
            int remaining = Integer.MAX_VALUE;
            int n;
            do {
                byte[] buf = new byte[Math.min(remaining, 1024 * 8)];
                int nread = 0;

                while ((n = in.read(buf, nread, Math.min(buf.length - nread, remaining))) > 0) {
                    nread += n;
                    remaining -= n;
                }

                if (nread > 0) {
                    result.writeBytes(buf, 0, nread);
                }
            } while (n >= 0 && remaining > 0);

            return TlDeserializer.deserialize(result);
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        } finally {
            result.release();
        }
    }

    public static int sumSizeExact(int base, ByteBuf... bufs) {
        for (ByteBuf buf : bufs) {
            base = Math.addExact(base, buf.readableBytes());
        }
        return base;
    }

    static ByteBuf readInt128(ByteBuf buf) {
        return buf.readSlice(Long.BYTES * 2);
    }

    static ByteBuf readInt256(ByteBuf buf) {
        return buf.readSlice(Long.BYTES * 4);
    }

    // sizeOf methods

    static int sizeOf0(String str) {
        int n = ByteBufUtil.utf8Bytes(str);
        int h = n >= 0xfe ? 4 : 1;
        int offset = (h + n) % 4;
        return h + n + 4 - offset;
    }

    static int sizeOf0(ByteBuf buf) {
        int n = buf.readableBytes();
        int h = n >= 0xfe ? 4 : 1;
        int offset = (h + n) % 4;
        return h + n + 4 - offset;
    }

    static int sizeOfIntVector(List<Integer> list) {
        return 8 + list.size() * 4;
    }

    static int sizeOfLongVector(List<Long> list) {
        return 8 + list.size() * 8;
    }

    static int sizeOfStringVector(List<String> list) {
        return sizeOfVector0(list, TlSerialUtil::sizeOf0);
    }

    static int sizeOfBytesVector(List<ByteBuf> list) {
        return sizeOfVector0(list, TlSerialUtil::sizeOf0);
    }

    static int sizeOfVector(List<? extends TlObject> list) {
        return sizeOfVector0(list, TlSerializer::sizeOf);
    }

    static int sizeOfUnknownVector(List<?> list) {
        return sizeOfVector0(list, TlSerialUtil::sizeOfUnknown);
    }

    static <T> int sizeOfVector0(int count, Iterator<T> it, ToIntFunction<T> func) {
        return StreamSupport.stream(Spliterators.spliterator(it, count, 0), false)
                .mapToInt(func)
                .reduce(0, Integer::sum);
    }

    static <T> int sizeOfVector0(List<T> list, ToIntFunction<T> func) {
        return 8 + list.stream().mapToInt(func).reduce(0, Integer::sum);
    }

    static int sizeOfFlags(Optional<?> o) {
        return o.map(TlSerialUtil::sizeOfUnknown).orElse(0);
    }

    static int sizeOfFlags(@Nullable Object o) {
        if (o == null) {
            return 0;
        }
        return sizeOfUnknown(o);
    }

    static int sizeOfUnknown(Object value) {
        if (value instanceof Integer || value instanceof Boolean) {
            return 4;
        } else if (value instanceof Long || value instanceof Double) {
            return 8;
        } else if (value instanceof ByteBuf) {
            return sizeOf0((ByteBuf) value);
        } else if (value instanceof String) {
            return sizeOf0((String) value);
        } else if (value instanceof List) {
            return sizeOfUnknownVector((List<?>) value);
        } else if (value instanceof TlObject) {
            return TlSerializer.sizeOf((TlObject) value);
        } else if (value instanceof JsonNode) {
            return sizeOfJsonNode((JsonNode) value);
        } else {
            throw new IllegalArgumentException("Incorrect TL serializable type: " + value + " (" + value.getClass() + ")");
        }
    }

    static int sizeOfJsonObjectValue(String name, JsonNode node) {
        return 4 + sizeOf0(name) + sizeOfJsonNode(node);
    }

    static int sizeOfJsonNode(JsonNode node) {
        switch (node.getNodeType()) {
            case NULL: return 4;
            case STRING: return 4 + sizeOf0(node.asText());
            case NUMBER: return 12;
            case BOOLEAN: return 8;
            case ARRAY: return 4 + sizeOfVector0(node.size(), node.elements(), TlSerialUtil::sizeOfJsonNode);
            case OBJECT: return 4 + sizeOfVector0(node.size(), node.fields(), e -> sizeOfJsonObjectValue(e.getKey(), e.getValue()));
            default: throw new IllegalStateException("Incorrect json node type: " + node.getNodeType());
        }
    }

    // serialization (returns new buffers)

    public static ByteBuf serializeString(ByteBufAllocator allocator, String value) {
        return serializeBytes(allocator, Unpooled.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static ByteBuf serializeBytes(ByteBufAllocator allocator, ByteBuf bytes) {
        int n = bytes.readableBytes();
        int h = n >= 0xfe ? 4 : 1;
        int offset = (h + n) % 4;
        ByteBuf buf = allocator.buffer(h + n + 4 - offset);

        if (n >= 0xfe) {
            buf.writeByte(0xfe);
            buf.writeMediumLE(n);
        } else {
            buf.writeByte(n);
        }

        buf.writeBytes(bytes, bytes.readerIndex(), n);
        if (offset != 0) {
            buf.writeZero(4 - offset);
        }

        return buf;
    }

    public static ByteBuf serializeLongVector(ByteBufAllocator allocator, List<Long> vector) {
        ByteBuf buf = allocator.buffer(8 + 8 * vector.size());
        buf.writeIntLE(VECTOR_ID);
        buf.writeIntLE(vector.size());
        for (long l : vector) {
            buf.writeLongLE(l);
        }
        return buf;
    }

    public static ByteBuf serializeIntVector(ByteBufAllocator allocator, List<Integer> vector) {
        ByteBuf buf = allocator.buffer(8 + 4 * vector.size());
        buf.writeIntLE(VECTOR_ID);
        buf.writeIntLE(vector.size());
        for (int i : vector) {
            buf.writeIntLE(i);
        }
        return buf;
    }

    public static ByteBuf serializeStringVector(ByteBufAllocator allocator, List<String> vector) {
        return serializeVector0(allocator, vector, e -> serializeString(allocator, e));
    }

    public static ByteBuf serializeBytesVector(ByteBufAllocator allocator, List<? extends ByteBuf> vector) {
        return serializeVector0(allocator, vector, e -> serializeBytes(allocator, e));
    }

    public static ByteBuf serializeVector(ByteBufAllocator allocator, List<? extends TlObject> vector) {
        return serializeVector0(allocator, vector, e -> TlSerializer.serialize(allocator, e));
    }

    public static ByteBuf serializeFlags(ByteBufAllocator allocator, Optional<?> value) {
        return value.map(o -> serializeUnknown(allocator, o)).orElse(Unpooled.EMPTY_BUFFER);
    }

    public static ByteBuf serializeFlags(ByteBufAllocator allocator, @Nullable Object value) {
        if (value == null) {
            return Unpooled.EMPTY_BUFFER;
        }
        return serializeUnknown(allocator, value);
    }

    public static ByteBuf serializeUnknown(ByteBufAllocator allocator, Object value) {
        if (value instanceof Integer) {
            return allocator.buffer(Integer.BYTES).writeIntLE((int) value);
        } else if (value instanceof Long) {
            return allocator.buffer(Long.BYTES).writeLongLE((long) value);
        } else if (value instanceof Boolean) {
            return allocator.buffer(Integer.BYTES).writeIntLE((boolean) value ? BOOL_TRUE_ID : BOOL_FALSE_ID);
        } else if (value instanceof Double) {
            return allocator.buffer(Double.BYTES).writeDoubleLE((long) value);
        } else if (value instanceof ByteBuf) {
            return serializeBytes(allocator, (ByteBuf) value);
        } else if (value instanceof String) {
            return serializeString(allocator, (String) value);
        } else if (value instanceof List) {
            return serializeVector0(allocator, (List<?>) value, e -> serializeUnknown(allocator, e));
        } else if (value instanceof TlObject) {
            return TlSerializer.serialize(allocator, (TlObject) value);
        } else if (value instanceof JsonNode) {
            return serializeJsonNode(allocator, (JsonNode) value);
        } else {
            throw new IllegalArgumentException("Incorrect TL serializable type: " + value + " (" + value.getClass() + ")");
        }
    }

    public static ByteBuf serializeJsonObjectValue(ByteBufAllocator allocator, String name, JsonNode value) {
        ByteBuf nameb = serializeString(allocator, name);
        ByteBuf valueb = serializeJsonNode(allocator, value);
        try {
            return allocator.buffer(sumSizeExact(4, nameb, valueb))
                    .writeIntLE(JSON_OBJECT_VALUE_ID)
                    .writeBytes(nameb)
                    .writeBytes(valueb);
        } finally {
            nameb.release();
            valueb.release();
        }
    }

    public static ByteBuf serializeJsonNode(ByteBufAllocator allocator, JsonNode node) {
        switch (node.getNodeType()) {
            case NULL: return allocator.buffer(Integer.BYTES).writeIntLE(JSON_NULL_ID);
            case STRING: {
                ByteBuf str = serializeString(allocator, node.asText());
                ByteBuf buf = allocator.buffer(4 + str.readableBytes());
                buf.writeIntLE(JSON_STRING_ID);
                buf.writeBytes(str);
                str.release();

                return buf;
            }
            case NUMBER: return allocator.buffer(12).writeIntLE(JSON_NUMBER_ID)
                    .writeDoubleLE(node.asDouble());
            case BOOLEAN: return allocator.buffer(8).writeIntLE(JSON_BOOL_ID)
                    .writeIntLE(node.asBoolean() ? BOOL_TRUE_ID : BOOL_FALSE_ID);
            case ARRAY: {
                ByteBuf vector = serializeVector0(allocator, node.size(), node.elements(), e -> serializeJsonNode(allocator, e));
                ByteBuf buf = allocator.buffer(4 + vector.readableBytes());
                buf.writeIntLE(JSON_ARRAY_ID);
                buf.writeBytes(vector);
                vector.release();

                return buf;
            }
            case OBJECT: {
                ByteBuf vector = serializeVector0(allocator, node.size(), node.fields(), e ->
                        serializeJsonObjectValue(allocator, e.getKey(), e.getValue()));
                ByteBuf buf = allocator.buffer(4 + vector.readableBytes());
                buf.writeIntLE(JSON_OBJECT_ID);
                buf.writeBytes(vector);
                vector.release();

                return buf;
            }
            default: throw new IllegalStateException("Incorrect json node type: " + node.getNodeType());
        }
    }

    static <T> ByteBuf serializeVector0(ByteBufAllocator allocator, int count, Iterator<T> it, Function<T, ByteBuf> serializer) {
        ByteBuf[] bytes = new ByteBuf[count];
        int size = 8;
        for (int i = 0; i < count; i++) {
            ByteBuf buf = serializer.apply(it.next());
            size = Math.addExact(size, buf.readableBytes());
            bytes[i] = buf;
        }

        ByteBuf buf = allocator.buffer(size);
        buf.writeIntLE(VECTOR_ID);
        buf.writeIntLE(count);
        for (ByteBuf b : bytes) {
            buf.writeBytes(b);
            b.release();
        }
        return buf;
    }

    static <T> ByteBuf serializeVector0(ByteBufAllocator allocator, List<T> vector, Function<T, ByteBuf> serializer) {
        ByteBuf[] bytes = new ByteBuf[vector.size()];
        int size = 8;
        for (int i = 0; i < bytes.length; i++) {
            ByteBuf buf = serializer.apply(vector.get(i));
            size = Math.addExact(size, buf.readableBytes());
            bytes[i] = buf;
        }

        ByteBuf buf = allocator.buffer(size);
        buf.writeIntLE(VECTOR_ID);
        buf.writeIntLE(vector.size());
        for (ByteBuf b : bytes) {
            buf.writeBytes(b);
            b.release();
        }
        return buf;
    }

    // some zero-copy variants of serializers

    static void serializeJsonObjectValue0(ByteBuf buf, String name, JsonNode value) {
        buf.writeIntLE(JSON_OBJECT_VALUE_ID);
        serializeString0(buf, name);
        serializeJsonNode0(buf, value);
    }

    static void serializeJsonNode0(ByteBuf buf, JsonNode node) {
        switch (node.getNodeType()) {
            case NULL:
                buf.writeIntLE(JSON_NULL_ID);
                break;
            case STRING:
                buf.writeIntLE(JSON_STRING_ID);
                serializeString0(buf, node.asText());
                break;
            case NUMBER:
                buf.writeIntLE(JSON_NUMBER_ID);
                buf.writeDoubleLE(node.asDouble());
                break;
            case BOOLEAN:
                buf.writeIntLE(JSON_BOOL_ID);
                buf.writeDoubleLE(node.asBoolean() ? BOOL_TRUE_ID : BOOL_FALSE_ID);
                break;
            case ARRAY:
                buf.writeIntLE(JSON_ARRAY_ID);
                serializeVector0(buf, node.size(), node.elements(), TlSerialUtil::serializeJsonNode0);
                break;
            case OBJECT:
                buf.writeIntLE(JSON_OBJECT_ID);
                serializeVector0(buf, node.size(), node.fields(), (b, e) ->
                        serializeJsonObjectValue0(b, e.getKey(), e.getValue()));
                break;
            default: throw new IllegalStateException("Incorrect json node type: " + node.getNodeType());
        }
    }

    static void serializeUnknown0(ByteBuf buf, Object value) {
        if (value instanceof Integer) {
            buf.writeIntLE((int) value);
        } else if (value instanceof Long) {
            buf.writeLongLE((long) value);
        } else if (value instanceof Boolean) {
            buf.writeIntLE((boolean) value ? BOOL_TRUE_ID : BOOL_FALSE_ID);
        } else if (value instanceof Double) {
            buf.writeDoubleLE((long) value);
        } else if (value instanceof ByteBuf) {
            serializeBytes0(buf, (ByteBuf) value);
        } else if (value instanceof String) {
            serializeString0(buf, (String) value);
        } else if (value instanceof List) {
            serializeVector0(buf, (List<?>) value, TlSerialUtil::serializeUnknown0);
        } else if (value instanceof TlObject) {
            TlSerializer.serialize0(buf, (TlObject) value);
        } else if (value instanceof JsonNode) {
            serializeJsonNode0(buf, (JsonNode) value);
        } else {
            throw new IllegalArgumentException("Incorrect TL serializable type: " + value + " (" + value.getClass() + ")");
        }
    }

    static void serializeFlags0(ByteBuf buf, Optional<?> value) {
        value.ifPresent(e -> serializeUnknown0(buf, e));
    }

    static void serializeFlags0(ByteBuf buf, @Nullable Object value) {
        if (value != null) {
            serializeUnknown0(buf, value);
        }
    }

    static void serializeStringVector0(ByteBuf buf, List<String> list) {
        serializeVector0(buf, list, TlSerialUtil::serializeString0);
    }

    static void serializeBytesVector0(ByteBuf buf, List<ByteBuf> list) {
        serializeVector0(buf, list, TlSerialUtil::serializeBytes0);
    }

    static void serializeLongVector0(ByteBuf buf, List<Long> list) {
        serializeVector0(buf, list, ByteBuf::writeLongLE);
    }

    static void serializeIntVector0(ByteBuf buf, List<Integer> list) {
        serializeVector0(buf, list, ByteBuf::writeIntLE);
    }

    static void serializeVector0(ByteBuf buf, List<? extends TlObject> list) {
        serializeVector0(buf, list, TlSerializer::serialize0);
    }

    static <T> void serializeVector0(ByteBuf buf, int count, Iterator<T> it, BiConsumer<ByteBuf, T> func) {
        buf.writeIntLE(VECTOR_ID);
        buf.writeIntLE(count);
        while (it.hasNext()) {
            func.accept(buf, it.next());
        }
    }

    static <T> void serializeVector0(ByteBuf buf, List<T> list, BiConsumer<ByteBuf, T> func) {
        serializeVector0(buf, list.size(), list.iterator(), func);
    }

    static void serializeString0(ByteBuf buf, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        int n = bytes.length;
        int h = n >= 0xfe ? 4 : 1;
        int offset = (h + n) % 4;

        if (n >= 0xfe) {
            buf.writeByte(0xfe);
            buf.writeMediumLE(n);
        } else {
            buf.writeByte(n);
        }

        buf.writeBytes(bytes);
        if (offset != 0) {
            buf.writeZero(4 - offset);
        }
    }

    static void serializeBytes0(ByteBuf buf, ByteBuf bytes) {
        int n = bytes.readableBytes();
        int h = n >= 0xfe ? 4 : 1;
        int offset = (h + n) % 4;

        if (n >= 0xfe) {
            buf.writeByte(0xfe);
            buf.writeMediumLE(n);
        } else {
            buf.writeByte(n);
        }

        buf.writeBytes(bytes);
        if (offset != 0) {
            buf.writeZero(4 - offset);
        }
    }

    // deserialization

    public static ByteBuf deserializeBytes(ByteBuf buf) {
        int n = buf.readUnsignedByte();
        int h = 1;
        if (n >= 0xfe) {
            n = buf.readUnsignedMediumLE();
            h = 4;
        }

        ByteBuf data = buf.readSlice(n);
        int offset = (n + h) % 4;
        if (offset != 0) {
            buf.skipBytes(4 - offset);
        }

        return data;
    }

    // optimized variant
    public static String deserializeString(ByteBuf buf) {
        int n = buf.readUnsignedByte();
        int h = 1;
        if (n >= 0xfe) {
            n = buf.readUnsignedMediumLE();
            h = 4;
        }

        ByteBuf data = buf.readSlice(n);
        int offset = (n + h) % 4;
        if (offset != 0) {
            buf.skipBytes(4 - offset);
        }

        return data.toString(StandardCharsets.UTF_8);
    }

    public static List<Long> deserializeLongVector(ByteBuf buf) {
        return deserializeVector0(buf, false, ByteBuf::readLongLE);
    }

    public static List<Integer> deserializeIntVector(ByteBuf buf) {
        return deserializeVector0(buf, false, ByteBuf::readIntLE);
    }

    public static List<String> deserializeStringVector(ByteBuf buf) {
        return deserializeVector0(buf, false, TlSerialUtil::deserializeString);
    }

    public static List<ByteBuf> deserializeBytesVector(ByteBuf buf) {
        return deserializeVector0(buf, false, TlSerialUtil::deserializeBytes);
    }

    public static <T> List<T> deserializeVector(ByteBuf buf) {
        return deserializeVector0(buf, false, TlDeserializer::deserialize);
    }

    public static JsonNode deserializeJsonNode(ByteBuf buf) {
        int identifier = buf.readIntLE();
        switch (identifier) {
            case JSON_NULL_ID: return NullNode.instance;
            case JSON_BOOL_ID: return BooleanNode.valueOf(buf.readIntLE() == BOOL_TRUE_ID);
            case JSON_STRING_ID: return TextNode.valueOf(deserializeString(buf));
            case JSON_NUMBER_ID: return DoubleNode.valueOf(buf.readDoubleLE());
            case JSON_ARRAY_ID: {
                int vectorId = buf.readIntLE();
                if (vectorId != VECTOR_ID) {
                    throw new IllegalStateException("Incorrect vector identifier: 0x" + Integer.toHexString(vectorId));
                }
                int size = buf.readIntLE();
                ArrayNode node = JsonNodeFactory.instance.arrayNode(size);
                for (int i = 0; i < size; i++) {
                    node.add(deserializeJsonNode(buf));
                }
                return node;
            }
            case JSON_OBJECT_ID: {
                int vectorId = buf.readIntLE();
                if (vectorId != VECTOR_ID) {
                    throw new IllegalStateException("Incorrect vector identifier: 0x" + Integer.toHexString(vectorId));
                }
                int size = buf.readIntLE();
                ObjectNode node0 = JsonNodeFactory.instance.objectNode();
                for (int i = 0; i < size; i++) {
                    if (buf.readIntLE() != JSON_OBJECT_VALUE_ID) {
                        throw new IllegalStateException("Incorrect json pair identifier: 0x" + Integer.toHexString(vectorId));
                    }
                    String name = deserializeString(buf);
                    node0.set(name, deserializeJsonNode(buf));
                }
                return node0;
            }
            default: throw new IllegalArgumentException("Incorrect json node identifier: 0x" + Integer.toHexString(identifier));
        }
    }

    static <T> List<T> deserializeVector0(ByteBuf buf, boolean bare, Function<? super ByteBuf, ? extends T> parser) {
        int vectorId;
        if (!bare && (vectorId = buf.readIntLE()) != VECTOR_ID) {
            throw new IllegalStateException("Incorrect vector identifier: 0x" + Integer.toHexString(vectorId));
        }
        int size = buf.readIntLE();
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(parser.apply(buf));
        }
        return list;
    }

    static List<Object> deserializeUnknownVector(ByteBuf buf) {
        // vector id skipped.
        int size = buf.readIntLE();
        List<Object> list = new ArrayList<>(size);
        boolean longVec = size * Long.BYTES == buf.readableBytes();
        boolean intVec = size * Integer.BYTES == buf.readableBytes();

        for (int i = 0; i < size; i++) {
            Object val;

            if (longVec) {
                val = buf.readLongLE();
            } else if (intVec) {
                val = buf.readIntLE();
            } else {
                val = TlDeserializer.deserialize(buf);
            }

            list.add(val);
        }
        return list;
    }

    /** Internal gzip output stream implementation which allows to change compression level. */
    static class CompressibleGZIPOutputStream extends GZIPOutputStream {

        public CompressibleGZIPOutputStream(OutputStream out, int level) throws IOException {
            super(out);
            def.setLevel(level);
        }
    }
}
