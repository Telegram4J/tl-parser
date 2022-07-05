package telegram4j.tl;

import io.netty.buffer.ByteBuf;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlObject;

import java.util.Optional;

public interface ChatPhotoFields extends TlObject {

    int flags();

    boolean hasVideo();

    long photoId();

    Optional<ByteBuf> strippedThumb();

    int dcId();
}
