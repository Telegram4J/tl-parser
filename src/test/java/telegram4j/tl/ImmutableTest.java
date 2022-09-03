package telegram4j.tl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ImmutableTest {

    @Test
    void with() {
        ImmutableBaseUser user = ImmutableBaseUser.of(0, 1337);

        Assertions.assertSame(user, user.withFlags(0));
        Assertions.assertSame(user, user.withBot(false));
        Assertions.assertSame(user, user.withAccessHash(null));
        var updated = user.withAccessHash(1L);
        Assertions.assertSame(updated, updated.withAccessHash(1L));
        Assertions.assertNotEquals(user, user.withRestrictionReason(List.of()));
        var emptyReasons = user.withRestrictionReason(List.of());
        Assertions.assertSame(emptyReasons, emptyReasons.withRestrictionReason(List.of()));
        Assertions.assertSame(user, user.withRestrictionReason((Iterable<? extends RestrictionReason>) null));
    }
}
