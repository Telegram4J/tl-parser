package telegram4j.tl.generator;

import io.netty.buffer.ByteBuf;
import reactor.util.function.Tuple2;
import telegram4j.tl.api.TlMethod;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.generator.renderer.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static reactor.util.function.Tuples.of;

final class SchemaGeneratorConsts {

    private SchemaGeneratorConsts() {
    }

    public static final int LAYER = 146;

    static final Pattern FLAG_PATTERN = Pattern.compile("^(\\w+)\\.(\\d+)\\?(.+)$");
    static final Pattern VECTOR_PATTERN = Pattern.compile("^[vV]ector<%?([\\w.<>]+)>$");
    // excluded from generation
    static final Set<String> ignoredTypes = Set.of("True", "Null", "HttpWait");
    // list of types whose ids will be in TlInfo
    static final Set<String> primitiveTypes = Set.of(
            "Bool", "Vector t", "JSONValue", "JSONObjectValue");

    static final String METHOD_PACKAGE_PREFIX = "request";
    static final String TEMPLATE_PACKAGE_INFO = "package-info.template";
    static final String BASE_PACKAGE = "telegram4j.tl";

    static final TypeVariableRef genericTypeRef = TypeVariableRef.of("T");
    static final TypeVariableRef genericResultTypeRef = TypeVariableRef.of("R");
    // <TlMethod<? extends R>>
    static final TypeRef wildcardMethodType = ParameterizedTypeRef.of(
            TlMethod.class, WildcardTypeRef.subtypeOf(genericResultTypeRef));

    static class Supertypes {
        static final List<Tuple2<Predicate<String>, ClassRef>> predicates = new ArrayList<>();

        private static void addPredicate(String supertype, Pattern regexp) {
            String qual = BASE_PACKAGE + '.' + supertype;
            String pck = SourceNames.parentPackageName(qual);
            ClassRef type = ClassRef.of(pck, qual.substring(pck.length() + 1));
            predicates.add(of(regexp.asMatchPredicate(), type));
        }

        private static void addPredicate(String supertype, String... subtype) {
            String qual = BASE_PACKAGE + '.' + supertype;
            String pck = SourceNames.parentPackageName(qual);
            ClassRef type = ClassRef.of(pck, qual.substring(pck.length() + 1));
            var set = Set.of(subtype);
            predicates.add(of(set::contains, type));
        }

        static {
            addPredicate("PtsUpdate",
                    "UpdateDeleteMessages",
                    "UpdateDeleteChannelMessages",
                    "UpdateNewChannelMessage",
                    "UpdateNewMessage",
                    "UpdatePinnedMessages",
                    "UpdatePinnedChannelMessages",
                    "UpdateReadHistoryOutbox",
                    "UpdateWebPage",
                    "UpdateReadMessagesContents",
                    "UpdateChannelWebPage",
                    "UpdateFolderPeers");

            addPredicate("BaseDocumentFields", "BaseDocument",
                    "BaseWebDocument",
                    "WebDocumentNoProxy");

            addPredicate("QtsUpdate", "UpdateNewEncryptedMessage",
                    "UpdateMessagePollVote",
                    "UpdateChatParticipant",
                    "UpdateChannelParticipant",
                    "UpdateBotStopped");

            addPredicate("UpdateDeleteMessagesFields", "UpdateDeleteScheduledMessages",
                    "UpdateDeleteMessages",
                    "UpdateDeleteChannelMessages");

            addPredicate("BaseMessageFields", "MessageService", "BaseMessage");

            addPredicate("request.messages.BaseSendMessageRequest", "SendMedia", "SendMessage");

            addPredicate("UpdateNewMessageFields", "UpdateNewMessage",
                    "UpdateNewChannelMessage",
                    "UpdateNewScheduledMessage");

            addPredicate("UpdatePinnedMessagesFields", "UpdatePinnedMessages", "UpdatePinnedChannelMessages");

            addPredicate("ChatPhotoFields", "BaseChatPhoto", "BaseUserProfilePhoto");

            addPredicate("UpdateEditMessageFields", "UpdateEditMessage", "UpdateEditChannelMessage");

            addPredicate("api.RpcMethod", "MsgDetailedInfo",
                    "MsgResendReq",
                    "MsgsAck",
                    "MsgsAllInfo",
                    "MsgsStateInfo",
                    "MsgsStateReq");

            addPredicate("api.EmptyObject", Pattern.compile(".*Empty"));
        }
    }

    // some interned types

    static final ClassRef LIST = ClassRef.of(List.class);
    static final ClassRef ITERABLE = ClassRef.of(Iterable.class);
    static final ClassRef STRING = ClassRef.of(String.class);
    static final ClassRef BYTE_BUF = ClassRef.of(ByteBuf.class);
    static final ClassRef TL_OBJECT = ClassRef.of(TlObject .class);

    // names style

    static class Style {
        static final Naming sizeVariable = Naming.from("*Size");
        static final Naming bitMask = Naming.from("*Mask");
        static final Naming bitPos = Naming.from("*Pos");

        static final Naming immutable = Naming.from("Immutable*");
        static final Naming add = Naming.from("add*");
        static final Naming addAll = Naming.from("addAll*");
        static final Naming with = Naming.from("with*");
        static final Naming set = Naming.from("set*");
        static final Naming newValue = Naming.from("new*Value");
        static final Naming initBit = Naming.from("initBit*");
    }
}
