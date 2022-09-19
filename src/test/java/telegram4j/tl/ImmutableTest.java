package telegram4j.tl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImmutableTest {

    @Test
    void with() {
        ImmutableBaseUser user = ImmutableBaseUser.of(0, 1337);

        assertSame(user, user.withFlags(0));
        assertSame(user, user.withBot(false));
        assertSame(user, user.withAccessHash(null));
        var updated = user.withAccessHash(1L);
        assertSame(updated, updated.withAccessHash(1L));
        assertNotEquals(user, user.withRestrictionReason(List.of()));
        var emptyReasons = user.withRestrictionReason(List.of());
        assertSame(emptyReasons, emptyReasons.withRestrictionReason(List.of()));
        assertSame(user, user.withRestrictionReason((Iterable<? extends RestrictionReason>) null));
        assertSame(user.withFlags(BaseUser.ACCESS_HASH_MASK), user);
        assertNotEquals(user.withFlags(BaseUser.BOT_MASK), user);
        assertNull(user.withFlags(BaseUser.BOT_MASK).botInfoVersion());
    }

    @Test
    void objectMethods() {

        Channel expected = Channel.builder()
                .id(1)
                .title("title")
                .photo(ChatPhotoEmpty.instance())
                .gigagroup(true)
                .date(1)
                .build();

        Channel actual = Channel.builder()
                .id(1)
                .title("title")
                .photo(ChatPhotoEmpty.instance())
                .gigagroup(true)
                .date(1)
                .build();

        assertEquals(expected.toString(), actual.toString());
        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
    }
}
