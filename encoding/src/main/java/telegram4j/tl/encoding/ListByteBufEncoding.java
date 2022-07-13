package telegram4j.tl.encoding;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.immutables.encode.Encoding;
import telegram4j.tl.api.TlEncodingUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Encoding
public class ListByteBufEncoding {

    @Encoding.Impl
    private final List<ByteBuf> value = null;

    @Override
    public String toString() {
        return value.stream().map(ByteBufUtil::hexDump).collect(Collectors.joining(", ", "[", "]"));
    }

    @Encoding.Expose
    public List<ByteBuf> get() {
        return value.stream().map(ByteBuf::duplicate).collect(Collectors.toList());
    }

    @Encoding.Of
    static List<ByteBuf> ofList(Iterable<? extends ByteBuf> value) {
        return StreamSupport.stream(value.spliterator(), false)
                .map(TlEncodingUtil::copyAsUnpooled)
                .collect(Collectors.toList());
    }

    @Encoding.Copy
    public List<ByteBuf> withList(Iterable<? extends ByteBuf> value) {
        return StreamSupport.stream(value.spliterator(), false)
                .map(TlEncodingUtil::copyAsUnpooled)
                .collect(Collectors.toList());
    }

    @Encoding.Builder
    static class Builder {

        private List<ByteBuf> value = null;

        private List<ByteBuf> getOrCreate() {
            if (value == null) {
                value = new ArrayList<>();
            }
            return value;
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
        public void setIterable(Iterable<? extends ByteBuf> value) {
            this.value = StreamSupport.stream(value.spliterator(), false)
                    .map(TlEncodingUtil::copyAsUnpooled)
                    .collect(Collectors.toList());
        }

        @Encoding.Init
        public void setVarargs(ByteBuf... value) {
            this.value = Arrays.stream(value)
                    .map(TlEncodingUtil::copyAsUnpooled)
                    .collect(Collectors.toList());
        }

        @Encoding.Build
        List<ByteBuf> build() {
            return TlEncodingUtil.unmodifiableList(value);
        }
    }
}
