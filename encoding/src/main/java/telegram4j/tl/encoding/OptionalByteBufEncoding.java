package telegram4j.tl.encoding;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.immutables.encode.Encoding;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlEncodingUtil;

import java.util.Optional;

@Encoding
public class OptionalByteBufEncoding {

    @Encoding.Impl
    private final ByteBuf value = null;

    @Encoding.Expose
    public Optional<ByteBuf> get() {
        return Optional.ofNullable(value).map(ByteBuf::duplicate);
    }

    @Override
    public String toString() {
        return get().map(ByteBufUtil::hexDump).toString();
    }

    @Encoding.Of
    static ByteBuf copy(Optional<ByteBuf> value) {
        return value.map(TlEncodingUtil::copyAsUnpooled).orElse(null);
    }

    @Encoding.Copy
    public ByteBuf withOptional(Optional<ByteBuf> value) {
        return value.map(TlEncodingUtil::copyAsUnpooled).orElse(null);
    }

    @Encoding.Copy
    public ByteBuf with(ByteBuf value) {
        return TlEncodingUtil.copyAsUnpooled(value);
    }

    @Encoding.Builder
    static class Builder {

        private ByteBuf value = null;

        @Encoding.Init
        public void setByteBuf(@Nullable ByteBuf value) {
            this.value = value != null ? TlEncodingUtil.copyAsUnpooled(value) : null;
        }

        @Encoding.Init
        @Encoding.Copy
        public void set(Optional<ByteBuf> value) {
            this.value = value.map(TlEncodingUtil::copyAsUnpooled).orElse(null);
        }

        @Encoding.Build
        ByteBuf build() {
            return value;
        }
    }
}
