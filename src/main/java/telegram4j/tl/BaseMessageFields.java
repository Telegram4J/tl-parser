package telegram4j.tl;

import reactor.util.annotation.Nullable;

public interface BaseMessageFields extends Message {

    boolean out();

    boolean mentioned();

    boolean mediaUnread();

    boolean silent();

    boolean post();

    boolean legacy();

    @Nullable
    Peer fromId();

    Peer peerId();

    @Nullable
    MessageReplyHeader replyTo();

    int date();

    @Nullable
    Integer ttlPeriod();
}
