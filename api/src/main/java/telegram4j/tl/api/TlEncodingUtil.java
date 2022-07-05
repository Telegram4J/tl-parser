package telegram4j.tl.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledHeapByteBuf;

/** Utility methods for encodings module. */
public class TlEncodingUtil {

    private TlEncodingUtil() {}

    public static ByteBuf copyAsUnpooled(ByteBuf value) {
        if (value.isReadOnly() && value.unwrap() instanceof UnpooledHeapByteBuf) {
            return value;
        }
        return Unpooled.copiedBuffer(value).asReadOnly();
    }
}
