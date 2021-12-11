package telegram4j.tl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import org.junit.jupiter.api.Test;
import telegram4j.tl.mtproto.GzipPacked;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SerializationTest {

    static ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;

    @Test
    void chat() {
        BaseChat expected = BaseChat.builder()
                .version(1)
                .date(1337)
                .title("A!")
                .id(10)
                .photo(ChatPhotoEmpty.instance())
                .participantsCount(99)
                .flags(0x2 | 0x18)
                .build();

        ByteBuf bytes = TlSerializer.serialize(alloc, expected);
        BaseChat result = TlDeserializer.deserialize(bytes);
        bytes.release();

        assertEquals(result, expected);
    }

    @Test
    void chatGziped() {
        Chat chat = ChatEmpty.builder()
                .id(1337)
                .build();

        GzipPacked pack = GzipPacked.builder()
                .packedData(ByteBufUtil.getBytes(TlSerialUtil.compressGzip(alloc, chat)))
                .build();

        ByteBuf serialized = TlSerializer.serialize(alloc, pack);
        GzipPacked packDeserialized = TlDeserializer.deserialize(serialized);
        serialized.release();

        ByteBuf deserialized = alloc.buffer().writeBytes(packDeserialized.packedData());
        Chat deserializedChat = TlSerialUtil.decompressGzip(deserialized);
        deserialized.release();

        assertEquals(chat, deserializedChat);
    }

    @Test
    void jsonNode() {
        TextNode n = TextNode.valueOf("test str");
        ByteBuf buf = TlSerialUtil.serializeJsonNode(alloc, n);
        JsonNode n0 = TlSerialUtil.deserializeJsonNode(buf);
        buf.release();

        assertEquals(n, n0);
    }
}
