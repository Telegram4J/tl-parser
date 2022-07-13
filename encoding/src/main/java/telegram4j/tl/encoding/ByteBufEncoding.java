package telegram4j.tl.encoding;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.immutables.encode.Encoding;
import telegram4j.tl.api.TlEncodingUtil;

import java.util.Objects;

@Encoding
public class ByteBufEncoding {

    @Encoding.Impl
    private final ByteBuf value = null;

    @Override
    public String toString() {
        return ByteBufUtil.hexDump(value);
    }

    @Encoding.Expose
    public ByteBuf get() {
        return value.duplicate();
    }

    @Encoding.Copy
    public ByteBuf withByteBuf(ByteBuf value) {
        return TlEncodingUtil.copyAsUnpooled(value);
    }

    @Encoding.Of
    static ByteBuf copyByteBuf(ByteBuf value) {
        return TlEncodingUtil.copyAsUnpooled(value);
    }

    @Encoding.Builder
    static class Builder {

        private ByteBuf value = null;

        @Encoding.Init
        @Encoding.Copy
        public void set(ByteBuf value) {
            this.value = TlEncodingUtil.copyAsUnpooled(value);
        }

        @Encoding.Build
        ByteBuf build() {
            return Objects.requireNonNull(value);
        }
    }
}
