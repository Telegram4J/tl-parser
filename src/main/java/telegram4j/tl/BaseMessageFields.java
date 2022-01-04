package telegram4j.tl;

import reactor.util.annotation.Nullable;

public interface BaseMessageFields extends Message {

    int flags();

    boolean out();

    boolean mentioned();

    boolean mediaUnread();

    boolean silent();

    boolean post();

    boolean legacy();

    int id();

    @Nullable
    Peer fromId();

    Peer peerId();

    @Nullable
    MessageFwdHeader fwdFrom();

    @Nullable
    MessageReplyHeader replyTo();

    int date();

    @Nullable
    Integer ttlPeriod();
}
