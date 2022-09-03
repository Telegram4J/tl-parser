package telegram4j.tl.generator;

import io.netty.buffer.ByteBuf;
import telegram4j.tl.api.TlMethod;
import telegram4j.tl.generator.renderer.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class SchemaGeneratorConsts {

    private SchemaGeneratorConsts() {
    }

    public static final int LAYER = 144;

    // channelFull has two flags fields
    static final Pattern FLAG_PATTERN = Pattern.compile("^(\\w+)\\.(\\d+)\\?(.+)$");
    static final Pattern VECTOR_PATTERN = Pattern.compile("^[vV]ector<%?([\\w.<>]+)>$");
    // excluded from generation
    static final Set<String> ignoredTypes = Set.of("True", "Null", "HttpWait");
    // list of types whose ids will be in TlInfo
    static final Set<String> primitiveTypes = Set.of(
            "Bool", "Vector t", "JSONValue", "JSONObjectValue");

    static final String METHOD_PACKAGE_PREFIX = "request";
    static final String TEMPLATE_PACKAGE_INFO = "package-info.template";
    static final String SUPERTYPES_DATA = "supertypes.json";
    static final String BASE_PACKAGE = "telegram4j.tl";

    static final TypeVariableRef genericTypeRef = TypeVariableRef.of("T");
    static final TypeVariableRef genericResultTypeRef = TypeVariableRef.of("R");
    // <R, T extends TlMethod<? extends R>>
    static final TypeRef wildcardMethodType = ParameterizedTypeRef.of(
            TlMethod.class, WildcardTypeRef.subtypeOf(genericResultTypeRef));

    static final List<NameTransformer> namingExceptions = List.of(
            NameTransformer.create("messages.StickerSet", "messages.StickerSetWithDocuments"));

    // some interned types

    static final ClassRef LIST = ClassRef.of(List.class);
    static final ClassRef ITERABLE = ClassRef.of(Iterable.class);
    static final ClassRef STRING = ClassRef.of(String.class);
    static final ClassRef BYTE_BUF = ClassRef.of(ByteBuf.class);

    // names style

    static class Style {
        static final Naming sizeVariable = Naming.from("*Size");
        static final Naming bitMask = Naming.from("*Mask");
        static final Naming bitPos = Naming.from("*Pos");

        static final Naming immutable = Naming.from("Immutable*");
        static final Naming add = Naming.from("add*");
        static final Naming addAll = Naming.from("addAll*");
        static final Naming with = Naming.from("with*");
        static final Naming newValue = Naming.from("new*Value");
        static final Naming initBit = Naming.from("initBit*");
    }
}
