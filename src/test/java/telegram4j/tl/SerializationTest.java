package telegram4j.tl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.mtproto.GzipPacked;
import telegram4j.tl.mtproto.ResPQ;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerializationTest {

    static ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;

    @Test
    void optionalFields() {
        Channel expected = Channel.builder()
                .id(1)
                .title("title")
                .photo(ImmutableChatPhotoEmpty.of())
                .gigagroup(true)
                .date(1)
                .build();

        ByteBuf bytes = TlSerializer.serialize(alloc, expected);
        Channel result = TlDeserializer.deserialize(bytes);
        bytes.release();

        assertEquals(result, expected);
    }

    @Test
    void chat() {
        BaseChat expected = BaseChat.builder()
                .version(1)
                .date(1337)
                .title("A!")
                .id(10)
                .photo(ImmutableChatPhotoEmpty.of())
                .participantsCount(99)
                .callNotEmpty(true)
                .build();

        BaseChat actual = serialize(expected);

        assertEquals(expected, actual);
    }

    @Test
    void chatGziped() {
        Chat expected = ChatEmpty.builder()
                .id(1337)
                .build();

        GzipPacked pack = GzipPacked.builder()
                .packedData(TlSerialUtil.compressGzip(alloc, Deflater.BEST_COMPRESSION, expected))
                .build();

        GzipPacked packDeserialized = serialize(pack);

        Chat actual = TlSerialUtil.decompressGzip(packDeserialized.packedData());

        assertEquals(expected, actual);
    }

    @Test
    void jsonNode() {
        TextNode expected = TextNode.valueOf("test str");
        ByteBuf buf = TlSerialUtil.serializeJsonNode(alloc, expected);
        JsonNode actual = TlSerialUtil.deserializeJsonNode(buf);
        buf.release();

        assertEquals(expected, actual);
    }

    @Test
    void byteBufAttribute() throws NoSuchAlgorithmException {
        SecureRandom rand = SecureRandom.getInstanceStrong();

        var expected = ResPQ.builder()
                .nonce(Unpooled.wrappedBuffer(rand.generateSeed(16)))
                .pq(Unpooled.wrappedBuffer(rand.generateSeed(32)))
                .serverNonce(Unpooled.wrappedBuffer(rand.generateSeed(16)))
                .serverPublicKeyFingerprints(List.of())
                .build();

        var actual = serialize(expected);

        assertEquals(expected, actual);
    }

    @Test
    void sizeOf() {
        Channel expected = Channel.builder()
                // ident. - 4
                // flags - 4
                .id(1) // 8
                .title("title") // 8
                .photo(ImmutableChatPhotoEmpty.of()) // 4
                .date(1) // 4
                .build();

        assertEquals(TlSerializer.sizeOf(expected), 32);
    }

    @Test
    void objectMethods() {
        Channel expected = Channel.builder()
                .id(1)
                .title("title")
                .photo(ImmutableChatPhotoEmpty.of())
                .gigagroup(true)
                .date(1)
                .build();

        Channel actual = Channel.builder()
                .id(1)
                .title("title")
                .photo(ImmutableChatPhotoEmpty.of())
                .gigagroup(true)
                .date(1)
                .build();

        assertEquals(expected.toString(), actual.toString());
        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
    }

    static <T extends TlObject> T serialize(T obj) {
        ByteBuf serialized = TlSerializer.serialize(alloc, obj);
        try {
            return TlDeserializer.deserialize(serialized);
        } finally {
            serialized.release();
        }
    }
}
