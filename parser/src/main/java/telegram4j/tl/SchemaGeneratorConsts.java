package telegram4j.tl;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeVariableName;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.model.ImmutableTlParam;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class SchemaGeneratorConsts {

    private SchemaGeneratorConsts() {
    }

    public static final Pattern FLAG_PATTERN = Pattern.compile("^flags\\.(\\d+)\\?(.+)$");
    public static final Pattern VECTOR_PATTERN = Pattern.compile("^[vV]ector<%?([A-Za-z0-9._<>]+)>$");

    public static final List<String> ignoredTypes = Arrays.asList(
            "bool", "true", "false", "null", "vector",
            "jsonvalue", "jsonobjectvalue", "httpwait");

    public static final List<String> primitiveTypes = Arrays.asList(
            "bool", "true", "vector", "jsonvalue", "jsonobjectvalue");

    public static final TypeVariableName genericType = TypeVariableName.get("T", TlObject.class);
    public static final TypeVariableName genericTypeRef = TypeVariableName.get("T");

    public static final ImmutableTlParam flagParameter = ImmutableTlParam.of("flags", "#");

    public static final MethodSpec privateConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

    public static final List<NameTransformer> namingExceptions = Arrays.asList(
            NameTransformer.create("messages.StickerSet", "messages.StickerSetWithDocuments"));
}
