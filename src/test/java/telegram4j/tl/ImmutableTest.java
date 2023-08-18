/*
 * Copyright 2023 Telegram4J
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package telegram4j.tl;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import telegram4j.tl.request.messages.ImmutableTranslateText;
import telegram4j.tl.request.messages.TranslateText;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImmutableTest {

    @Test
    void withMethods() {
        ImmutableBaseUser user = ImmutableBaseUser.of(0, 0, 1337);

        // assert shallow equals check in with*() method
        assertSame(user, user.withFlags(0));
        assertSame(user, user.withAccessHash(null));
        var updated = user.withAccessHash(1L);
        assertSame(updated, updated.withAccessHash(1L));
        assertNotEquals(user, user.withRestrictionReason(List.of()));
        var emptyReasons = user.withRestrictionReason(List.of());
        assertSame(emptyReasons, emptyReasons.withRestrictionReason(List.of()));
        assertSame(user, user.withRestrictionReason((Iterable<? extends RestrictionReason>) null));
        // assert that withFlags() cleans value masks
        assertSame(user.withFlags(BaseUser.ACCESS_HASH_MASK), user);
        // BaseUser#bot() shares bit-position with BaseUser#botInfoVersion()
        // and with*() methods must depend in these situations only on value attributes
        assertSame(user.withFlags(BaseUser.BOT_MASK), user);
        assertNull(user.withFlags(BaseUser.BOT_MASK).botInfoVersion());
        var bicpsrp = ImmutableBaseInputCheckPasswordSRP.builder()
                .a(Unpooled.EMPTY_BUFFER)
                .m1(Unpooled.copyInt(1, 2, 3))
                .srpId(123)
                .build();
        // FIXME: this is not exactly an error, it is a consequence of what we return .duplicate()
        assertNotSame(bicpsrp.withA(Unpooled.EMPTY_BUFFER), bicpsrp);
        var tr = ImmutableTranslateText.of("en")
                .withPeer(InputPeerEmpty.instance());
        // FIXME: expected behavior, but tg servers will return protocol errors
        // Need to find a way to correctly validate these attributes in with* methods
        assertNull(tr.id());
    }

    @Test
    void dualBitInit() {
        assertThrowsExactly(IllegalStateException.class, () -> TranslateText.builder()
                .addId(1)
                // forgot to set .peer() attribute
                .toLang("en")
                .build());
        TranslateText.builder()
                .peer(InputPeerEmpty.instance())
                .addId(1)
                .toLang("en")
                .build();
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
