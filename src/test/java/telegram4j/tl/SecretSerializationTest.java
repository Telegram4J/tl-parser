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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import telegram4j.tl.api.SecretObject;
import telegram4j.tl.secret.*;
// single-type imports, otherwise the same-named types of the api scheme,
// which this test package belongs to, would shadow the imported-on-demand ones
import telegram4j.tl.secret.DocumentAttributeVideo;
import telegram4j.tl.secret.MessageEntityBold;
import telegram4j.tl.secret.SendMessageAction;

import static org.junit.jupiter.api.Assertions.*;

class SecretSerializationTest {

    static ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;

    @Test
    void decryptedMessage() {
        var expected = BaseDecryptedMessage.builder()
                .randomId(0x1337)
                .ttl(0)
                .message("hi")
                .silent(true)
                .build();

        assertEquals(expected, serialize(expected));
    }

    @Test
    void decryptedMessageLayer() {
        var expected = DecryptedMessageLayer.builder()
                .randomBytes(Unpooled.wrappedBuffer(new byte[16]))
                .layer(SecretTlInfo.LAYER)
                .inSeqNo(0)
                .outSeqNo(1)
                .message(BaseDecryptedMessage.builder()
                        .randomId(0x1337)
                        .ttl(60)
                        .message("hi")
                        .addEntity(MessageEntityBold.builder()
                                .offset(0)
                                .length(2)
                                .build())
                        .media(DecryptedMessageMediaEmpty.instance())
                        .build())
                .build();

        assertEquals(expected, serialize(expected));
    }

    @Test
    void vectorsStayInScheme() {
        // messageEntityBold#bd610bc9 exists in both schemes and MessageEntity vectors
        // must not be dispatched through TlDeserializer, which would yield an api entity
        var expected = DecryptedMessage46.builder()
                .randomId(0x1337)
                .ttl(0)
                .message("hi")
                .addEntity(MessageEntityBold.builder()
                        .offset(0)
                        .length(2)
                        .build())
                .build();

        var actual = SecretSerializationTest.<DecryptedMessage46>serialize(expected);

        assertEquals(expected, actual);
        assertInstanceOf(MessageEntityBold.class, actual.entities().get(0));
    }

    @Test
    void collidingIdentifiersAreIndependent() {
        assertEquals(MessageEntityBold.ID, telegram4j.tl.MessageEntityBold.ID);

        assertEquals(MessageEntityBold.class, SecretTlInfo.typeOf(MessageEntityBold.ID));
        assertEquals(telegram4j.tl.MessageEntityBold.class, TlInfo.typeOf(MessageEntityBold.ID));

        // documentAttributeVideo diverged between the schemes at layer 66
        assertNotEquals(DocumentAttributeVideo.ID, telegram4j.tl.DocumentAttributeVideo.ID);
        assertThrowsExactly(IllegalArgumentException.class, () -> TlInfo.typeOf(DocumentAttributeVideo.ID));
    }

    @Test
    void actions() {
        var expected = DecryptedMessageService.builder()
                .randomId(0x1337)
                .action(DecryptedMessageActionNotifyLayer.builder()
                        .layer(SecretTlInfo.LAYER)
                        .build())
                .build();

        assertEquals(expected, serialize(expected));

        var typing = DecryptedMessageActionTyping.builder()
                .action(SendMessageAction.TYPING_ACTION)
                .build();

        assertEquals(typing, serialize(typing));

        var read = DecryptedMessageActionReadMessages.builder()
                .addRandomIds(1L, 2L, 3L)
                .build();

        assertEquals(read, serialize(read));
    }

    static <T extends SecretObject> T serialize(T obj) {
        ByteBuf serialized = SecretTlSerializer.serialize(alloc, obj);
        assertEquals(SecretTlSerializer.sizeOf(obj), serialized.readableBytes());
        try {
            return SecretTlDeserializer.deserialize(serialized);
        } finally {
            serialized.release();
        }
    }
}
