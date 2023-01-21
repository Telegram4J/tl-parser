package telegram4j.tl.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledHeapByteBuf;
import reactor.util.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

    public static int mask(int flags, int mask, boolean state) {
        return state ? flags | mask : flags & ~mask;
    }

    public static boolean eq(boolean present, boolean value, @Nullable Boolean newValue) {
        return !present && newValue == null || newValue != null && newValue == value;
    }

    public static boolean eq(boolean present, int value, @Nullable Integer newValue) {
        return !present && newValue == null || newValue != null && newValue == value;
    }

    public static boolean eq(boolean present, long value, @Nullable Long newValue) {
        return !present && newValue == null || newValue != null && newValue == value;
    }

    public static boolean eq(boolean present, double value, @Nullable Double newValue) {
        return !present && newValue == null || newValue != null && newValue.equals(value);
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> copyList(Iterable<? extends T> values) {
        if (values instanceof Collection<?>) {
            return List.copyOf((Collection<? extends T>) values);
        }
        return StreamSupport.stream(values.spliterator(), false)
                .collect(Collectors.toUnmodifiableList());
    }
}
