package telegram4j.tl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.json.TlModule;
import telegram4j.tl.request.InvokeWithLayer;
import telegram4j.tl.request.help.GetConfig;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JsonSerializationTest {

    static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new TlModule());

    @Test
    void byteBuf() throws Throwable {
        SecureRandom rnd = SecureRandom.getInstanceStrong();
        byte[] b = new byte[1024];
        rnd.nextBytes(b);

        ByteBuf exp = Unpooled.wrappedBuffer(b);
        ByteBuf act = serialize(exp, ByteBuf.class);
        assertEquals(exp, act);
    }

    @Test
    void allCases() throws Throwable {

        assertSame(NotificationSoundNone.instance(), serialize(NotificationSoundNone.instance()));
        assertSame(TopPeerCategory.BOTS_INLINE, serialize(TopPeerCategory.BOTS_INLINE));
        var expAccountDaysTTL = AccountDaysTTL.builder()
                .days(1213)
                .build();
        assertEquals(expAccountDaysTTL, serialize(expAccountDaysTTL));
        var expInvokeWithLayer = InvokeWithLayer.builder()
                .layer(1)
                .query(GetConfig.instance())
                .build();
        assertEquals(expInvokeWithLayer, serialize(expInvokeWithLayer));
        var expBaseUser = BaseUser.builder()
                .id(7331)
                .botInfoVersion(23)
                .addRestrictionReason(RestrictionReason.builder()
                        .platform("linux123456")
                        .reason("a")
                        .text("text")
                        .build())
                .build();
        assertEquals(expBaseUser, serialize(expBaseUser));
        var expUpdateNewMessage = UpdateNewMessage.builder()
                .pts(2)
                .message(BaseMessage.builder()
                        .id(1234)
                        .message("text text text text text")
                        .date((int) System.currentTimeMillis())
                        .peerId(ImmutablePeerChat.of(312312))
                        .build())
                .ptsCount(1)
                .build();
        assertEquals(expUpdateNewMessage, serialize(expUpdateNewMessage));
    }

    static <T> T serialize(T o, TypeReference<? extends T> ptype) throws Throwable {
        String s = mapper.writeValueAsString(o);
        return mapper.readValue(s, ptype);
    }

    static <T> T serialize(T o, Class<? extends T> klass) throws Throwable {
        String s = mapper.writeValueAsString(o);
        return mapper.readValue(s, klass);
    }

    @SuppressWarnings("unchecked")
    static <T extends TlObject> T serialize(T o) throws Throwable {
        return (T) serialize(o, TlObject.class);
    }
}
