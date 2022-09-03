package telegram4j.tl.encoding;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.immutables.encode.Encoding;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlEncodingUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Encoding
public class OptionalListByteBufEncoding {
    @Encoding.Impl
    private final List<ByteBuf> value = null;

    @Encoding.Expose
    public Optional<List<ByteBuf>> get() {
        return Optional.ofNullable(value).map(l -> l.stream()
                .map(ByteBuf::duplicate)
                .collect(Collectors.toList()));
    }

    @Override
    public String toString() {
        return get().map(l -> l.stream().map(ByteBufUtil::hexDump).collect(Collectors.joining(", ", "[", "]"))).toString();
    }

    @Encoding.Of
    static List<ByteBuf> copy(Optional<? extends Iterable<ByteBuf>> value) {
        return value.map(i -> StreamSupport.stream(i.spliterator(), false)
                .map(TlEncodingUtil::copyAsUnpooled)
                .collect(Collectors.toList()))
                .orElse(null);
    }

    @Encoding.Copy
    public List<ByteBuf> withOptional(Optional<? extends Iterable<ByteBuf>> value) {
        return value.map(i -> StreamSupport.stream(i.spliterator(), false)
                        .map(TlEncodingUtil::copyAsUnpooled)
                        .collect(Collectors.toList()))
                .orElse(null);
    }

    @Encoding.Copy
    public List<ByteBuf> with(Iterable<? extends ByteBuf> value) {
        return StreamSupport.stream(value.spliterator(), false)
                .map(TlEncodingUtil::copyAsUnpooled)
                .collect(Collectors.toList());
    }

    @Encoding.Builder
    static class Builder {

        private Optional<List<ByteBuf>> value = Optional.empty();

        private List<ByteBuf> getOrCreate() {
            if (value.isEmpty()) {
                value = Optional.of(new ArrayList<>());
            }
            return value.orElseThrow();
        }

        @Encoding.Naming(standard = Encoding.StandardNaming.ADD)
        @Encoding.Init
        public void add(ByteBuf value) {
            getOrCreate().add(TlEncodingUtil.copyAsUnpooled(value));
        }

        @Encoding.Naming(standard = Encoding.StandardNaming.ADD_ALL)
        @Encoding.Init
        public void addAll(Iterable<? extends ByteBuf> value) {
            getOrCreate().addAll(StreamSupport.stream(value.spliterator(), false)
                    .map(TlEncodingUtil::copyAsUnpooled)
                    .collect(Collectors.toList()));
        }

        @Encoding.Naming(standard = Encoding.StandardNaming.ADD_ALL)
        @Encoding.Init
        public void addAllVarargs(ByteBuf... value) {
            getOrCreate().addAll(Arrays.stream(value)
                    .map(TlEncodingUtil::copyAsUnpooled)
                    .collect(Collectors.toList()));
        }

        @Encoding.Init
        @Encoding.Copy
        public void set(Optional<? extends Iterable<ByteBuf>> value) {
            this.value = value.map(i -> StreamSupport.stream(i.spliterator(), false)
                    .map(TlEncodingUtil::copyAsUnpooled)
                    .collect(Collectors.toList()));
        }

        @Encoding.Init
        public void setIterable(@Nullable Iterable<? extends ByteBuf> value) {
            this.value = Optional.ofNullable(value)
                    .map(i -> StreamSupport.stream(i.spliterator(), false)
                            .map(TlEncodingUtil::copyAsUnpooled)
                            .collect(Collectors.toList()));
        }

        @Encoding.Init
        public void setVarargs(@Nullable ByteBuf... value) {
            this.value = Optional.ofNullable(value)
                    .map(b -> Arrays.stream(value)
                            .map(TlEncodingUtil::copyAsUnpooled)
                            .collect(Collectors.toList()));
        }

        @Encoding.Build
        List<ByteBuf> build() {
            return value.map(TlEncodingUtil::copyList).orElse(null);
        }
    }
}
