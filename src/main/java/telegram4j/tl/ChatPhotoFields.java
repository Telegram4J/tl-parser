package telegram4j.tl;

import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlObject;

public interface ChatPhotoFields extends TlObject {

    default int flags() {
        return (hasVideo() ? 1 : 0) << 0x0 | (strippedThumb() != null ? 1 : 0) << 0x1;
    }

    default boolean hasVideo() {
        return false;
    }

    long photoId();

    @Nullable
    byte[] strippedThumb();

    int dcId();
}
