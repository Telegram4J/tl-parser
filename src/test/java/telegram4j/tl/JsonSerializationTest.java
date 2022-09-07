package telegram4j.tl;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.IndexedListSerializer;
import org.junit.jupiter.api.Test;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.json.TlModule;
import telegram4j.tl.request.InvokeWithLayer;
import telegram4j.tl.request.help.GetConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JsonSerializationTest {

    static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new TlModule());

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
    }

    @SuppressWarnings("unchecked")
    static <T extends TlObject> T serialize(T o) throws Throwable {
        String s = mapper.writeValueAsString(o);
        return (T) mapper.readValue(s, TlObject.class);
    }
}
