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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static telegram4j.tl.TlPrimitives.*;

/** Intrinsics serialization methods used in the scheme parser. */
public final class TlSerialUtil {

    private TlSerialUtil() {
    }

    public static ByteBuf compressGzip(ByteBufAllocator allocator, ByteBuf buf) {
        ByteBufOutputStream bufOut = new ByteBufOutputStream(allocator.buffer(buf.readableBytes()));
        try (DeflaterOutputStream out = new CompressibleGZIPOutputStream(bufOut)) {
            out.write(ByteBufUtil.getBytes(buf));
            out.finish();
            buf.release();
            return bufOut.buffer();
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
    }

    public static ByteBuf compressGzip(ByteBufAllocator allocator, TlObject object) {
        return compressGzip(allocator, TlSerializer.serialize(allocator, object));
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

    public static ByteBuf readInt128(ByteBuf buf) {
        return buf.readBytes(Long.BYTES * 2);
    }

    public static ByteBuf readInt256(ByteBuf buf) {
        return buf.readBytes(Long.BYTES * 4);
    }

    // serialization

    public static ByteBuf serializeString(ByteBufAllocator allocator, String value) {
        return serializeBytes(allocator, Unpooled.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static ByteBuf serializeBytes(ByteBufAllocator allocator, ByteBuf bytes) {
        int n = bytes.readableBytes();
        int header = n >= 0xfe ? 4 : 1;
        int offset = (header + n) % 4;
        ByteBuf buf = allocator.buffer(header + n + 4 - offset);

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
            List<?> value0 = (List<?>) value;
            return serializeVector0(allocator, value0, e -> serializeUnknown(allocator, e));
        } else if (value instanceof TlObject) {
            return TlSerializer.serialize(allocator, (TlObject) value);
        } else if (value instanceof JsonNode) {
            return serializeJsonNode(allocator, (JsonNode) value);
        } else {
            throw new IllegalArgumentException("Incorrect TL serializable type: " + value + " (" + value.getClass() + ")");
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
                ByteBuf buf = allocator.buffer();
                buf.writeIntLE(JSON_ARRAY_ID);
                buf.writeIntLE(VECTOR_ID);
                buf.writeIntLE(node.size());
                node.elements().forEachRemaining(n -> {
                    ByteBuf value = serializeJsonNode(allocator, n);
                    buf.writeBytes(value);
                    value.release();
                });
                return buf;
            }
            case OBJECT: {
                ByteBuf buf = allocator.buffer();
                buf.writeIntLE(JSON_OBJECT_ID);
                buf.writeIntLE(VECTOR_ID);
                buf.writeIntLE(node.size());
                node.fields().forEachRemaining(f -> {
                    buf.writeIntLE(JSON_OBJECT_VALUE_ID);
                    ByteBuf name = serializeString(allocator, f.getKey());
                    buf.writeBytes(name);
                    name.release();
                    ByteBuf value = serializeJsonNode(allocator, f.getValue());
                    buf.writeBytes(value);
                    value.release();
                });
                return buf;
            }
            default: throw new IllegalStateException("Incorrect json node type: " + node.getNodeType());
        }
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

    // deserialization

    public static ByteBuf deserializeBytes(ByteBuf buf) {
        int n = buf.readUnsignedByte();
        int h = 1;
        if (n >= 0xfe) {
            n = buf.readUnsignedMediumLE();
            h = 4;
        }

        ByteBuf data = buf.readBytes(n);
        int offset = (n + h) % 4;
        if (offset != 0) {
            buf.skipBytes(4 - offset);
        }

        return data;
    }

    public static String deserializeString(ByteBuf buf) {
        return deserializeBytes(buf).toString(StandardCharsets.UTF_8);
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

    /** Internal gzip output stream implementation, which has compression level {@code == Deflater.BEST_COMPRESSION} for best compression. */
    static class CompressibleGZIPOutputStream extends GZIPOutputStream {

        public CompressibleGZIPOutputStream(OutputStream out) throws IOException {
            super(out);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
