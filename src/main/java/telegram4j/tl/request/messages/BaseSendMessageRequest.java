package telegram4j.tl.request.messages;

import reactor.util.annotation.Nullable;
import telegram4j.tl.*;
import telegram4j.tl.api.TlMethod;

import java.util.List;

public interface BaseSendMessageRequest extends TlMethod<Updates> {

    InputPeer peer();

    @Nullable
    Integer replyToMsgId();

    String message();

    long randomId();

    @Nullable
    ReplyMarkup replyMarkup();

    @Nullable
    List<MessageEntity> entities();

    @Nullable
    Integer scheduleDate();
}
