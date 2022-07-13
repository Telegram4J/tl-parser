package telegram4j.tl.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledHeapByteBuf;
import reactor.util.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Utility methods for encodings module. */
public class TlEncodingUtil {

    private TlEncodingUtil() {}

    public static ByteBuf copyAsUnpooled(ByteBuf value) {
        // TODO: replace with new type
        if (value.unwrap() != null && value.unwrap().isReadOnly() &&
            value.unwrap().unwrap() instanceof UnpooledHeapByteBuf) {
            return value;
        }
        return Unpooled.unreleasableBuffer(Unpooled.copiedBuffer(value).asReadOnly());
    }

    public static <T> List<T> unmodifiableList(@Nullable List<? extends T> list) {
        if (list == null) {
            return Collections.emptyList();
        }

        switch (list.size()) {
            case 0:
                return Collections.emptyList();
            case 1:
                return Collections.singletonList(list.get(0));
            default:
                if (list instanceof ArrayList<?>) {
                    ((ArrayList<?>) list).trimToSize();
                }
                return Collections.unmodifiableList(list);
        }
    }
}
