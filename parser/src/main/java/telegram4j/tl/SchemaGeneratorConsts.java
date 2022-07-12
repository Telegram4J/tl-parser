package telegram4j.tl;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeVariableName;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.json.ImmutableTlParam;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class SchemaGeneratorConsts {

    private SchemaGeneratorConsts() {
    }

    public static final Pattern FLAG_PATTERN = Pattern.compile("^flags\\.(\\d+)\\?(.+)$");
    public static final Pattern VECTOR_PATTERN = Pattern.compile("^[vV]ector<%?([\\w.<>]+)>$");

    public static final Set<String> ignoredTypes = Set.of("True", "Null", "HttpWait");

    public static final Set<String> primitiveTypes = Set.of(
            "Bool", "Vector t", "JSONValue", "JSONObjectValue");

    public static final TypeVariableName genericType = TypeVariableName.get("T", TlObject.class);
    public static final TypeVariableName genericTypeRef = TypeVariableName.get("T");

    public static final ImmutableTlParam flagParameter = ImmutableTlParam.of("flags", "#");

    public static final MethodSpec privateConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

    public static final List<NameTransformer> namingExceptions = List.of(
            NameTransformer.create("messages.StickerSet", "messages.StickerSetWithDocuments"));
}
