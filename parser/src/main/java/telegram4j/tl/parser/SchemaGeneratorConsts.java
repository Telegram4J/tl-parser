package telegram4j.tl.parser;

import com.squareup.javapoet.*;
import telegram4j.tl.api.TlMethod;
import telegram4j.tl.api.TlObject;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class SchemaGeneratorConsts {

    private SchemaGeneratorConsts() {
    }

    // channelFull has two flags fields
    public static final Pattern FLAG_PATTERN = Pattern.compile("^(\\w+)\\.(\\d+)\\?(.+)$");
    public static final Pattern VECTOR_PATTERN = Pattern.compile("^[vV]ector<%?([\\w.<>]+)>$");
    // excluded from generation
    public static final Set<String> ignoredTypes = Set.of("True", "Null", "HttpWait");
    // list of types whose ids will be in TlPrimitives
    public static final Set<String> primitiveTypes = Set.of(
            "Bool", "Vector t", "JSONValue", "JSONObjectValue");

    public static final TypeVariableName genericTypeRef = TypeVariableName.get("T");
    public static final TypeVariableName genericResultTypeRef = TypeVariableName.get("R");
    public static final TypeName wildcardMethodType = ParameterizedTypeName.get(
            ClassName.get(TlMethod.class), WildcardTypeName.subtypeOf(genericResultTypeRef));
    public static final TypeName wildcardUnboundedMethodType = ParameterizedTypeName.get(
            ClassName.get(TlMethod.class), WildcardTypeName.subtypeOf(TypeName.OBJECT));
    public static final TypeVariableName genericType = TypeVariableName.get("T", TlObject.class);

    public static final MethodSpec privateConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

    public static final List<NameTransformer> namingExceptions = List.of(
            NameTransformer.create("messages.StickerSet", "messages.StickerSetWithDocuments"));
}
