package telegram4j.tl;

import io.netty.buffer.ByteBuf;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlObject;

public interface ChatPhotoFields extends TlObject {

    int flags();

    boolean hasVideo();

    long photoId();

    @Nullable
    ByteBuf strippedThumb();

    int dcId();
}
