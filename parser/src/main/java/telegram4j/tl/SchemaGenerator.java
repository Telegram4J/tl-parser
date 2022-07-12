package telegram4j.tl;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.squareup.javapoet.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.immutables.value.Value;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.*;
import telegram4j.tl.json.ImmutableTlSchema;
import telegram4j.tl.json.TlEntityObject;
import telegram4j.tl.json.TlParam;
import telegram4j.tl.json.TlSchema;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static telegram4j.tl.SchemaGeneratorConsts.*;
import static telegram4j.tl.SourceNames.normalizeName;
import static telegram4j.tl.SourceNames.parentPackageName;
import static telegram4j.tl.Strings.camelize;
import static telegram4j.tl.Strings.screamilize;

@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedAnnotationTypes("telegram4j.tl.GenerateSchema")
public class SchemaGenerator extends AbstractProcessor {

    // TODO: add serialization for JsonVALUE in TlSerializer.serialize(...)

    private static final String METHOD_PACKAGE_PREFIX = "request";
    private static final String MTPROTO_PACKAGE_PREFIX = "mtproto";
    private static final String TEMPLATE_PACKAGE_INFO = "package-info.template";
    private static final String BASE_PACKAGE = "telegram4j.tl";
    private static final String API_SCHEMA = "api.json";
    private static final String MTPROTO_SCHEMA = "mtproto.json";
    private static final String INDENT = "\t";

    private final Map<String, TlEntityObject> computed = new HashMap<>();

    private PackageElement currentElement;
    private TlSchema apiSchema;
    private TlSchema mtprotoSchema;
    private List<TlSchema> schemas;
    private Map<TlSchema, Map<String, List<TlEntityObject>>> typeTree;
    private Map<String, List<TlEntityObject>> concTypeTree;

    private int iteration;
    private int schemaIteration;
    private TlSchema schema;
    private Map<String, List<TlEntityObject>> currTypeTree;

    // processing resources

    private final Set<String> computedSerializers = new HashSet<>();
    private final Set<String> computedDeserializers = new HashSet<>();
    private final Set<String> computedMethodSerializers = new HashSet<>();

    private final List<TlEntityObject> singletons = new ArrayList<>(200);

    private final TypeSpec.Builder serializer = TypeSpec.classBuilder("TlSerializer")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateConstructor);

    private final MethodSpec.Builder serializeMethod = MethodSpec.methodBuilder("serialize")
            .returns(ByteBuf.class)
            .addTypeVariable(genericType)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(ByteBufAllocator.class, "alloc")
            .addParameter(genericType, "payload")
            .beginControlFlow("switch (payload.identifier())");

    private final TypeSpec.Builder deserializer = TypeSpec.classBuilder("TlDeserializer")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateConstructor);

    private final MethodSpec.Builder deserializeMethod = MethodSpec.methodBuilder("deserialize")
            .returns(genericTypeRef)
            .addTypeVariable(genericTypeRef)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(ByteBuf.class, "payload")
            .addStatement("int identifier = payload.readIntLE()")
            .beginControlFlow("switch (identifier)")
            // This need because methods can return bool or vector objects
            .addCode("case BOOL_TRUE_ID: return (T) Boolean.TRUE;\n")
            .addCode("case BOOL_FALSE_ID: return (T) Boolean.FALSE;\n")
            .addCode("case VECTOR_ID: return (T) deserializeUnknownVector(payload);\n");

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        ObjectMapper mapper = JsonMapper.builder()
                .addModules(new Jdk8Module())
                .visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .visibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY)
                .visibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
                .build();

        try {
            InputStream api = processingEnv.getFiler().getResource(
                    StandardLocation.ANNOTATION_PROCESSOR_PATH, "", API_SCHEMA).openInputStream();
            InputStream mtproto = processingEnv.getFiler().getResource(
                    StandardLocation.ANNOTATION_PROCESSOR_PATH, "", MTPROTO_SCHEMA).openInputStream();

            apiSchema = ImmutableTlSchema.copyOf(mapper.readValue(api, TlSchema.class));

            mtprotoSchema = ImmutableTlSchema.copyOf(mapper.readValue(mtproto, TlSchema.class))
                    .withPackagePrefix(MTPROTO_PACKAGE_PREFIX)
                    .withSuperType(MTProtoObject.class);

            schemas = Arrays.asList(apiSchema, mtprotoSchema);
        } catch (Throwable t) {
            throw Exceptions.propagate(t);
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }

        if (annotations.size() > 1) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[TL parser] Generation package must be specified once!", currentElement);
            return true;
        }

        if (currentElement == null) {
            currentElement = (PackageElement) roundEnv
                    .getElementsAnnotatedWith(GenerateSchema.class)
                    .iterator().next();
        }

        if (typeTree == null) {
            var api = collectTypeTree(apiSchema);
            var mtproto = collectTypeTree(mtprotoSchema);
            typeTree = Map.of(apiSchema, api, mtprotoSchema, mtproto);
            concTypeTree = typeTree.values().stream()
                    .reduce(new HashMap<>(), (l, r) -> {
                        l.putAll(r);
                        return l;
                    });

            preparePackages();
        }

        switch (iteration) {
            case 0:
                generatePrimitives();
                schema = schemas.get(schemaIteration);
                currTypeTree = typeTree.get(schema);
                iteration++;
                break;
            case 1:
                generateSuperTypes();
                iteration++;
                break;
            case 2:
                generateConstructors();
                iteration++;
                break;
            case 3:
                generateMethods();
                if (++schemaIteration >= schemas.size()) {
                    iteration = 4;
                } else { // *new* generation round
                    schema = schemas.get(schemaIteration);
                    currTypeTree = typeTree.get(schema);
                    iteration = 1;
                }
                break;
            case 4:
                finalizeSerialization();
                iteration++; // end
                break;
        }

        return true;
    }

    private void finalizeSerialization() {

        for (var ent : concTypeTree.entrySet()) {
            boolean isEnum = ent.getValue().stream()
                    .mapToInt(c -> c.params().size())
                    .sum() == 0;

            if (!isEnum) {
                continue;
            }

            String packageName = parentPackageName(ent.getKey());
            var chunk = ent.getValue();
            for (int i = 0; i < chunk.size(); i++) {
                TlEntityObject obj = chunk.get(i);

                String idStr = Integer.toHexString(obj.id());
                if (i + 1 == chunk.size()) {
                    String type = normalizeName(obj.type());
                    deserializeMethod.addCode("case 0x$L: return (T) $T.of(identifier);\n", idStr,
                            ClassName.get(packageName, type));
                } else {
                    deserializeMethod.addCode("case 0x$L:\n", idStr);
                }
                serializeMethod.addCode("case 0x$L:\n", idStr);
            }
        }

        for (int i = 0; i < singletons.size(); i++) {
            TlEntityObject obj = singletons.get(i);
            String id = Integer.toHexString(obj.id());
            if (i + 1 == singletons.size()) {
                serializeMethod.addCode("case 0x$L: return alloc.buffer(4).writeIntLE(payload.identifier());\n", id);
            } else {
                serializeMethod.addCode("case 0x$L:\n", id);
            }
        }

        serializeMethod.addCode("default: throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()));\n",
                "Incorrect TlObject identifier: 0x");
        serializeMethod.endControlFlow();
        serializer.addMethod(serializeMethod.build());

        writeTo(JavaFile.builder(getBasePackageName(), serializer.build())
                .addStaticImport(ClassName.get(BASE_PACKAGE, "TlSerialUtil"), "*")
                .addStaticImport(ClassName.get(getBasePackageName(), "TlPrimitives"), "*")
                .indent(INDENT)
                .skipJavaLangImports(true)
                .build());

        deserializeMethod.addCode("default: throw new IllegalArgumentException($S + Integer.toHexString(identifier));\n",
                "Incorrect TlObject identifier: 0x");
        deserializeMethod.endControlFlow();
        deserializer.addMethod(deserializeMethod.build());

        writeTo(JavaFile.builder(getBasePackageName(), deserializer.build())
                .addStaticImport(ClassName.get(BASE_PACKAGE, "TlSerialUtil"), "*")
                .addStaticImport(ClassName.get(getBasePackageName(), "TlPrimitives"), "*")
                .indent(INDENT)
                .skipJavaLangImports(true)
                .build());
    }

    private void generateMethods() {
        for (TlEntityObject method : schema.methods()) {
            String name = normalizeName(method.name());
            if (ignoredTypes.contains(method.type())) {
                continue;
            }

            String packageName = getPackageName(schema, method.name(), true);

            boolean generic = method.params().stream()
                    .anyMatch(p -> p.type().equals("!X"));

            TypeSpec.Builder spec = TypeSpec.interfaceBuilder(name)
                    .addModifiers(Modifier.PUBLIC);

            if (generic) {
                spec.addTypeVariable(TypeVariableName.get("T", schema.superType()));
            }

            TypeName returnType = ParameterizedTypeName.get(
                    ClassName.get(TlMethod.class),
                    parseType(method.type(), schema).box());

            spec.addSuperinterfaces(awareSuperType(name));

            spec.addSuperinterface(returnType);
            if (schema.superType() != TlObject.class) {
                spec.addSuperinterface(schema.superType());
            }

            spec.addField(FieldSpec.builder(TypeName.INT, "ID",
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + Integer.toHexString(method.id()))
                    .build());

            ClassName immutableTypeRaw = ClassName.get(packageName, "Immutable" + name);
            ClassName immutableTypeBuilderRaw = ClassName.get(packageName, "Immutable" + name, "Builder");
            TypeName immutableType = generic ? ParameterizedTypeName.get(immutableTypeRaw, genericTypeRef) : immutableTypeRaw;
            TypeName immutableBuilderType = generic
                    ? ParameterizedTypeName.get(immutableTypeBuilderRaw, genericTypeRef)
                    : immutableTypeBuilderRaw;

            MethodSpec.Builder builder = MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(immutableBuilderType)
                    .addCode("return $T.builder();", immutableTypeRaw);

            if (generic) {
                builder.addTypeVariable(TypeVariableName.get("T", schema.superType()));
            }

            spec.addMethod(builder.build());

            AnnotationSpec.Builder value = AnnotationSpec.builder(Value.Immutable.class);

            boolean singleton = method.params().stream().allMatch(p -> p.type().equals("#") || p.type().startsWith("flags."));
            if (singleton) {
                value.addMember("singleton", "true");

                MethodSpec.Builder instance = MethodSpec.methodBuilder("instance")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(immutableType)
                        .addCode("return $T.of();", immutableTypeRaw);

                if (generic) {
                    instance.addTypeVariable(TypeVariableName.get("T", schema.superType()));
                }

                spec.addMethod(instance.build());
            }

            spec.addAnnotation(value.build());

            spec.addMethod(MethodSpec.methodBuilder("identifier")
                    .addAnnotation(Override.class)
                    .returns(TypeName.INT)
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .addCode("return ID;")
                    .build());

            // serialization
            String methodName0 = "serialize" + name;
            String methodName = methodName0;
            if (computedMethodSerializers.contains(methodName0)) {
                String prx = schema.packagePrefix();
                if (prx.isEmpty()) {
                    String mname = method.name();
                    prx = camelize(mname.substring(0, mname.lastIndexOf('.')));
                }
                char up = prx.charAt(0);
                methodName = "serialize" + Character.toUpperCase(up) + prx.substring(1) + name;
            }

            computedMethodSerializers.add(methodName);

            ClassName typeRaw = ClassName.get(packageName, name);
            TypeName type = generic ? ParameterizedTypeName.get(typeRaw, ClassName.get(schema.superType())) : typeRaw;

            if (method.params().isEmpty()) {
                singletons.add(method);
            } else {

                serializeMethod.addCode("case 0x$L: return $L(alloc, ($T) payload);\n",
                        Integer.toHexString(method.id()), methodName, type);

                TypeName payloadType = generic ? ParameterizedTypeName.get(typeRaw, genericTypeRef) : typeRaw;

                MethodSpec.Builder serializerBuilder = MethodSpec.methodBuilder(methodName)
                        .returns(ByteBuf.class)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameters(Arrays.asList(
                                ParameterSpec.builder(ByteBufAllocator.class, "alloc").build(),
                                ParameterSpec.builder(payloadType, "payload").build()));

                if (generic) {
                    serializerBuilder.addTypeVariable(TypeVariableName.get("T", schema.superType()));
                }

                boolean hasReleasable = method.params().stream()
                        .anyMatch(p -> isReleasable(p.type()));

                CodeBlock.Builder serFinallyBlock = CodeBlock.builder();
                CodeBlock.Builder serPrecomputeBlock = CodeBlock.builder();
                CodeBlock.Builder std = CodeBlock.builder();

                int size = 4;
                StringJoiner releasableSized = new StringJoiner(", ");
                for (TlParam param : method.params()) {
                    boolean releasable = isReleasable(param.type());

                    String fixedParamName = param.formattedName().equals("payload")
                            ? "payload$" : param.formattedName();

                    int s = sizeOf(param.type());
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else {
                        releasableSized.add(fixedParamName);
                    }

                    // serialization
                    String ser = serializeMethod(param);
                    String met = byteBufMethod(param);
                    if (ser != null) {
                        if (releasable) {
                            serPrecomputeBlock.addStatement("$T $L = " + ser, ByteBuf.class,
                                    fixedParamName, param.formattedName());
                            serFinallyBlock.addStatement("$L.release()", fixedParamName);
                            std.add("\n\t\t.$L($L)", met, fixedParamName);
                        } else {
                            std.add("\n\t\t." + met + "(" + ser + ")", param.formattedName());
                        }
                    }

                    TypeName paramType = parseType(param.type(), schema);

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC);

                    if (param.type().equals("#")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        String precompute = method.params().stream()
                                .filter(p -> p.flagInfo().isPresent())
                                .map(f -> {
                                    var flagInfo = f.flagInfo().orElseThrow();
                                    return String.format("(%s()%s ? 1 : 0) << 0x%x", f.formattedName(),
                                            flagInfo.getT2().equals("true") ? "" : " != null", flagInfo.getT1());
                                })
                                .collect(Collectors.joining(" | "));

                        attribute.addCode("return " + precompute + ";");
                    } else if (param.type().endsWith("true")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        attribute.addCode("return false;");
                    } else if (param.type().startsWith("flags.")) {
                        paramType = wrapOptional(attribute, paramType, param);
                        attribute.addModifiers(Modifier.ABSTRACT);
                    } else {
                        attribute.addModifiers(Modifier.ABSTRACT);
                    }

                    spec.addMethod(attribute
                            .returns(paramType)
                            .build());
                }

                serializerBuilder.addCode(serPrecomputeBlock.build());

                if (hasReleasable) {
                    serializerBuilder.beginControlFlow("try");
                }

                String sizeStr = releasableSized.length() == 0
                        ? Integer.toString(size)
                        : "sumSizeExact(" + size + ", " + releasableSized + ")";

                serializerBuilder.addCode("return alloc.buffer($L)\n", sizeStr);
                serializerBuilder.addCode("\t\t.writeIntLE(payload.identifier())");
                serializerBuilder.addCode(std.add(";").build());

                if (hasReleasable) {
                    serializerBuilder.addCode("\n");
                    serializerBuilder.nextControlFlow("finally");
                    serializerBuilder.addCode(serFinallyBlock.build());
                    serializerBuilder.endControlFlow();
                }

                serializer.addMethod(serializerBuilder.build());
            }

            writeTo(JavaFile.builder(packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());
        }
    }

    private void generateConstructors() {
        for (TlEntityObject constructor : schema.constructors()) {
            String type = normalizeName(constructor.type());
            if (ignoredTypes.contains(constructor.type()) || primitiveTypes.contains(constructor.type())) {
                continue;
            }

            String name = normalizeName(constructor.name());
            String packageName = getPackageName(schema, constructor.type(), false);
            String qualifiedTypeName = packageName + "." + type;

            boolean multiple = currTypeTree.getOrDefault(qualifiedTypeName, List.of()).size() > 1;

            // add Base* prefix to prevent matching with type name, e.g. SecureValueError
            if (type.equalsIgnoreCase(name) && multiple) {
                name = "Base" + name;
            } else if (!multiple && !type.equals("Object")) { // use type name if this object type is singleton and type isn't equals Object
                name = type;
            }

            // Check if type has already been done
            if (computed.containsKey(packageName + "." + name)) {
                continue;
            }

            TypeSpec.Builder spec = TypeSpec.interfaceBuilder(name)
                    .addModifiers(Modifier.PUBLIC)
                    .addSuperinterfaces(awareSuperType(name));

            if (multiple) {
                spec.addSuperinterface(ClassName.get(packageName, type));
            } else {
                spec.addSuperinterface(schema.superType());
            }

            spec.addField(FieldSpec.builder(TypeName.INT, "ID",
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + Integer.toHexString(constructor.id()))
                    .build());

            spec.addMethod(MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.get(packageName, "Immutable" + name, "Builder"))
                    .addCode("return Immutable$L.builder();", name)
                    .build());

            var attributes = new LinkedHashSet<>(constructor.params());
            collectAttributesRecursive(type, attributes);

            boolean singleton = attributes.stream().allMatch(p -> p.type().startsWith("flags.") || p.type().equals("#"));
            AnnotationSpec.Builder value = AnnotationSpec.builder(Value.Immutable.class);
            if (singleton) {
                value.addMember("singleton", "true");

                spec.addMethod(MethodSpec.methodBuilder("instance")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(ClassName.get(packageName, "Immutable" + name))
                        .addCode("return Immutable$L.of();", name)
                        .build());
            }
            spec.addAnnotation(value.build());

            spec.addMethod(MethodSpec.methodBuilder("identifier")
                    .addAnnotation(Override.class)
                    .returns(TypeName.INT)
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .addCode("return ID;")
                    .build());

            // serialization
            // Check serialization method name collision
            String serializeMethodName = "serialize" + name;
            if (computedSerializers.contains(serializeMethodName)) {
                String prx = schema.packagePrefix();
                if (prx.isEmpty()) {
                    prx = camelize(parentPackageName(constructor.name()));
                }
                char up = prx.charAt(0);
                serializeMethodName = "serialize" + Character.toUpperCase(up) + prx.substring(1) + name;
            }

            computedSerializers.add(serializeMethodName);

            TypeName payloadType = ClassName.get(packageName, name);

            // deserialization
            String deserializeMethodName = "deserialize" + name;
            if (computedDeserializers.contains(deserializeMethodName)) {
                String prx = schema.packagePrefix();
                if (prx.isEmpty()) {
                    prx = camelize(parentPackageName(constructor.name()));
                }
                char up = prx.charAt(0);
                deserializeMethodName = "deserialize" + Character.toUpperCase(up) + prx.substring(1) + name;
            }

            computedDeserializers.add(deserializeMethodName);

            TypeName typeName = ClassName.get(packageName, "Immutable" + name);
            if (attributes.isEmpty()) {
                deserializeMethod.addCode("case 0x$L: return (T) $T.of();\n",
                        Integer.toHexString(constructor.id()), typeName);

                singletons.add(constructor);
            } else {
                serializeMethod.addCode("case 0x$L: return $L(alloc, ($T) payload);\n",
                        Integer.toHexString(constructor.id()), serializeMethodName, payloadType);

                deserializeMethod.addCode("case 0x$L: return (T) $L(payload);\n",
                        Integer.toHexString(constructor.id()), deserializeMethodName);

                MethodSpec.Builder deserializerBuilder = MethodSpec.methodBuilder(deserializeMethodName)
                        .returns(typeName)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(ParameterSpec.builder(ByteBuf.class, "payload").build());

                boolean hasReleasable = attributes.stream()
                        .anyMatch(p -> isReleasable(p.type()));

                CodeBlock.Builder serFinallyBlock = CodeBlock.builder();
                CodeBlock.Builder serPrecomputeBlock = CodeBlock.builder();
                CodeBlock.Builder std = CodeBlock.builder();
                if (attributes.contains(flagParameter)) {
                    deserializerBuilder.addStatement("int flags = payload.readIntLE()");
                }
                deserializerBuilder.addCode("return $T.builder()", typeName);

                int size = 4;
                StringJoiner releasableSized = new StringJoiner(", ");
                for (TlParam param : attributes) {
                    boolean releasable = isReleasable(param.type());
                    String fixedParamName = param.formattedName().equals("payload")
                            ? "payload$" : param.formattedName();

                    int s = sizeOf(param.type());
                    if (s != -1) {
                         size = Math.addExact(size, s);
                    } else {
                        releasableSized.add(fixedParamName);
                    }

                    // serialization
                    String ser = serializeMethod(param);
                    String met = byteBufMethod(param);
                    if (ser != null) {
                        if (releasable) {
                            serPrecomputeBlock.addStatement("$T $L = " + ser, ByteBuf.class,
                                    fixedParamName, param.formattedName());
                            serFinallyBlock.addStatement("$L.release()", fixedParamName);
                            std.add("\n\t\t.$L($L)", met, fixedParamName);
                        } else {
                            std.add("\n\t\t." + met + "(" + ser + ")", param.formattedName());
                        }
                    }

                    deserializerBuilder.addCode("\n\t\t.$L(" + deserializeMethod(param) + ")", param.formattedName());

                    TypeName paramType = parseType(param.type(), schema);

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC);

                    if (param.type().equals("#")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        String precompute = constructor.params().stream()
                                .filter(p -> p.flagInfo().isPresent())
                                .map(f -> {
                                    var flagInfo = f.flagInfo().orElseThrow();
                                    return String.format("(%s()%s ? 1 : 0) << 0x%x", f.formattedName(),
                                            flagInfo.getT2().equals("true") ? "" : " != null", flagInfo.getT1());
                                })
                                .collect(Collectors.joining(" | "));

                        attribute.addCode("return " + precompute + ";");
                    } else if (param.type().endsWith("true")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        attribute.addCode("return false;");
                    } else if (param.type().startsWith("flags.")) {
                        paramType = wrapOptional(attribute, paramType, param);
                        attribute.addModifiers(Modifier.ABSTRACT);
                    } else {
                        attribute.addModifiers(Modifier.ABSTRACT);
                    }

                    spec.addMethod(attribute.returns(paramType)
                            .build());
                }

                deserializer.addMethod(deserializerBuilder.addCode("\n\t\t.build();").build());

                var serializerBuilder = MethodSpec.methodBuilder(serializeMethodName)
                        .returns(ByteBuf.class)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameters(Arrays.asList(
                                ParameterSpec.builder(ByteBufAllocator.class, "alloc").build(),
                                ParameterSpec.builder(payloadType, "payload").build()))
                        .addCode(serPrecomputeBlock.build());

                if (hasReleasable) {
                    serializerBuilder.beginControlFlow("try");
                }

                String sizeStr = releasableSized.length() == 0
                        ? Integer.toString(size)
                        : "sumSizeExact(" + size + ", " + releasableSized + ")";

                serializerBuilder.addCode("return alloc.buffer($L)\n", sizeStr);
                serializerBuilder.addCode("\t\t.writeIntLE(payload.identifier())");
                serializerBuilder.addCode(std.add(";").build());

                if (hasReleasable) {
                    serializerBuilder.addCode("\n");
                    serializerBuilder.nextControlFlow("finally");
                    serializerBuilder.addCode(serFinallyBlock.build());
                    serializerBuilder.endControlFlow();
                }

                serializer.addMethod(serializerBuilder.build());
            }

            writeTo(JavaFile.builder(packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());

            computed.put(packageName + "." + name, constructor);
        }
    }

    private boolean isReleasable(String type) {
        switch (type.toLowerCase()) {
            case "#":
            case "int":
            case "true":
            case "bool":
            case "long":
            case "double":
                return false;
            default:
                return true;
        }
    }

    private int sizeOf(String type) {
        switch (type.toLowerCase()) {
            case "int256":
                return 32;
            case "int128":
                return 16;
            case "long":
            case "double":
                return 8;
            case "int":
            case "bool":
            case "#":
                return 4;
            default:
                if (type.endsWith("true")) {
                    return 0; // to skip param
                }
                return -1;
        }
    }

    private void generateSuperTypes() {
        var superTypes = currTypeTree.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.flatMapping(e -> e.getValue().stream()
                                        .flatMap(c -> c.params().stream())
                                        .filter(p -> e.getValue().stream()
                                                .allMatch(c -> c.params().contains(p))),
                                Collectors.toCollection(LinkedHashSet::new))));

        for (var e : superTypes.entrySet()) {
            String name = normalizeName(e.getKey());
            Set<TlParam> params = e.getValue();
            String packageName = parentPackageName(e.getKey());
            String qualifiedName = e.getKey();

            boolean canMakeEnum = currTypeTree.get(qualifiedName).stream()
                    .mapToInt(c -> c.params().size()).sum() == 0 &&
                    !name.equals("Object");

            String shortenName = extractEnumName(qualifiedName);

            TypeSpec.Builder spec = canMakeEnum
                    ? TypeSpec.enumBuilder(name)
                    : TypeSpec.interfaceBuilder(name);

            spec.addModifiers(Modifier.PUBLIC);
            spec.addSuperinterface(schema.superType());

            if (canMakeEnum) {

                MethodSpec.Builder ofMethod = MethodSpec.methodBuilder("of")
                        .addParameter(int.class, "identifier")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(ClassName.get(packageName, name))
                        .beginControlFlow("switch (identifier)");

                for (TlEntityObject constructor : currTypeTree.get(qualifiedName)) {
                    String subtypeName = normalizeName(constructor.name());
                    String constName = screamilize(subtypeName.substring(shortenName.length()));

                    spec.addEnumConstant(constName, TypeSpec.anonymousClassBuilder(
                            "0x$L", Integer.toHexString(constructor.id()))
                            .build());

                    ofMethod.addCode("case 0x$L: return $L;\n", Integer.toHexString(constructor.id()), constName);

                    computed.put(packageName + "." + subtypeName, constructor);
                }

                spec.addField(int.class, "identifier", Modifier.PRIVATE, Modifier.FINAL);

                spec.addMethod(MethodSpec.constructorBuilder()
                        .addParameter(int.class, "identifier")
                        .addStatement("this.identifier = identifier")
                        .build());

                ofMethod.addCode("default: throw new IllegalArgumentException($S + Integer.toHexString(identifier));\n",
                        "Incorrect type identifier: 0x");
                ofMethod.endControlFlow();

                spec.addMethod(MethodSpec.methodBuilder("identifier")
                        .addAnnotation(Override.class)
                        .returns(TypeName.INT)
                        .addModifiers(Modifier.PUBLIC)
                        .addCode("return identifier;")
                        .build());

                spec.addMethod(ofMethod.build());
            } else {

                for (TlParam param : params) {
                    TypeName paramType = parseType(param.type(), schema);

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

                    boolean optionalInExt = currTypeTree.getOrDefault(qualifiedName, List.of()).stream()
                            .flatMap(c -> c.params().stream())
                            .anyMatch(p -> p.type().startsWith("flags.") &&
                                    p.name().equals(param.name()));

                    if (!param.type().endsWith("true") && (param.type().startsWith("flags.") || optionalInExt)) {
                        paramType = wrapOptional(attribute, paramType, param);
                    }

                    spec.addMethod(attribute.returns(paramType).build());
                }
            }

            writeTo(JavaFile.builder(packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());
        }
    }

    private void generatePrimitives() {

        TypeSpec.Builder spec = TypeSpec.classBuilder("TlPrimitives")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(privateConstructor);

        for (TlEntityObject e : apiSchema.constructors()) {
            if (primitiveTypes.contains(e.type())) {
                String name = screamilize(e.name()) + "_ID";

                spec.addField(FieldSpec.builder(int.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("0x" + Integer.toHexString(e.id()))
                        .build());
            }
        }

        writeTo(JavaFile.builder(getBasePackageName(), spec.build())
                .indent(INDENT)
                .skipJavaLangImports(true)
                .build());
    }

    private void preparePackages() {
        try {

            String processingPackageName = getBasePackageName();
            String template = processingEnv.getFiler().getResource(StandardLocation.ANNOTATION_PROCESSOR_PATH,
                            "", TEMPLATE_PACKAGE_INFO).getCharContent(true)
                    .toString();

            var packages = concTypeTree.keySet().stream()
                    .map(SourceNames::parentPackageName)
                    .filter(s -> !s.equals(processingPackageName))
                    .collect(Collectors.toSet());

            Function<TlSchema, Set<String>> methodPackagesCollector = schema -> schema.methods().stream()
                    .map(e -> getPackageName(schema, e.name(), true))
                    .collect(Collectors.toSet());

            packages.addAll(methodPackagesCollector.apply(apiSchema));
            packages.addAll(methodPackagesCollector.apply(mtprotoSchema));

            for (String pack : packages) {
                String complete = template.replace("${package-name}", pack);
                try (Writer writer = processingEnv.getFiler().createSourceFile(pack + ".package-info").openWriter()) {
                    writer.write(complete);
                    writer.flush();
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare packages", e);
        }
    }

    private TypeName parseType(String type, TlSchema schema) {
        switch (type.toLowerCase()) {
            case "!x":
            case "x": return genericType;
            case "#":
            case "int": return TypeName.INT;
            case "true":
            case "bool": return TypeName.BOOLEAN;
            case "long": return TypeName.LONG;
            case "double": return TypeName.DOUBLE;
            case "bytes":
            case "int128":
            case "int256": return ClassName.get(ByteBuf.class);
            case "string": return ClassName.get(String.class);
            case "object": return ClassName.OBJECT;
            case "jsonvalue": return ClassName.get(JsonNode.class);
            default:
                Matcher flag = FLAG_PATTERN.matcher(type);
                if (flag.matches()) {
                    String innerTypeRaw = flag.group(2);
                    TypeName t = parseType(innerTypeRaw, schema);
                    return innerTypeRaw.equals("true") ? t : t.box();
                }

                Matcher vector = VECTOR_PATTERN.matcher(type);
                if (vector.matches()) {
                    TypeName templateType = parseType(vector.group(1), schema);
                    return ParameterizedTypeName.get(ClassName.get(List.class), templateType.box());
                }

                String packageName = getPackageName(schema, type, false);
                return ClassName.get(packageName, normalizeName(type));
        }
    }

    private void collectAttributesRecursive(String name, Set<TlParam> params) {
        TlEntityObject constructor = computed.get(name);
        if (constructor == null || constructor.name().equals(name)) {
            return;
        }
        params.addAll(constructor.params());
        collectAttributesRecursive(constructor.name(), params);
    }

    private String getBasePackageName() {
        return currentElement.getQualifiedName().toString();
    }

    private void writeTo(JavaFile file) {
        try {
            file.writeTo(processingEnv.getFiler());
        } catch (Throwable t) {
            throw Exceptions.propagate(t);
        }
    }

    private String extractEnumName(String type) {
        return concTypeTree.getOrDefault(type, List.of()).stream()
                .map(c -> normalizeName(c.name()))
                .reduce(Strings::findCommonPart)
                .orElseGet(() -> normalizeName(type));
    }

    private String deserializeMethod(TlParam param) {

        var flagInfo = param.flagInfo().orElse(null);
        if (flagInfo != null) {
            int position = flagInfo.getT1();
            String typeRaw = flagInfo.getT2();

            String pos = Integer.toHexString(1 << position);
            if (typeRaw.equals("true")) {
                return "(flags & 0x" + pos + ") != 0";
            }

            String innerMethod = deserializeMethod0(typeRaw);
            return "(flags & 0x" + pos + ") != 0 ? " + innerMethod + " : null";
        }

        return deserializeMethod0(param.type());
    }

    private String deserializeMethod0(String type) {
        switch (type.toLowerCase()) {
            case "true": return "true";
            case "#": return "flags";
            case "bool": return "payload.readIntLE() == BOOL_TRUE_ID";
            case "int": return "payload.readIntLE()";
            case "long": return "payload.readLongLE()";
            case "double": return "payload.readDoubleLE()";
            case "bytes": return "deserializeBytes(payload)";
            case "string": return "deserializeString(payload)";
            case "int128": return "readInt128(payload)";
            case "int256": return "readInt256(payload)";
            case "jsonvalue": return "deserializeJsonNode(payload)";
            default:
                Matcher vector = VECTOR_PATTERN.matcher(type);
                if (vector.matches()) {
                    String innerType = vector.group(1).toLowerCase();
                    String specific = "";
                    switch (innerType) {
                        case "int":
                        case "long":
                        case "bytes":
                        case "string":
                            specific = Character.toUpperCase(innerType.charAt(0)) + innerType.substring(1);
                            break;
                    }

                    // NOTE: bare vectors (msg_container, future_salts)
                    if (type.contains("%")) {
                        return "deserializeVector0(payload, true, TlDeserializer::deserializeMessage)";
                    } else if (type.contains("future_salt")) {
                        return "deserializeVector0(payload, true, TlDeserializer::deserializeFutureSalt)";
                    }
                    return "deserialize" + specific + "Vector(payload)";
                }
                return "deserialize(payload)";
        }
    }

    private String byteBufMethod(TlParam param) {
        String paramTypeLower = param.type().toLowerCase();
        switch (paramTypeLower) {
            case "bool":
            case "#":
            case "int": return "writeIntLE";
            case "long": return "writeLongLE";
            case "double": return  "writeDoubleLE";
            default: return "writeBytes";
        }
    }

    @Nullable
    private String serializeMethod(TlParam param) {
        String paramTypeLower = param.type().toLowerCase();
        switch (paramTypeLower) {
            case "int":
            case "#":
            case "long":
            case "int128":
            case "int256":
            case "double": return "payload.$L()";
            case "bool": return "payload.$L() ? BOOL_TRUE_ID : BOOL_FALSE_ID";
            case "string": return "serializeString(alloc, payload.$L())";
            case "bytes": return "serializeBytes(alloc, payload.$L())";
            case "jsonvalue": return "serializeJsonNode(alloc, payload.$L())";
            case "object": return "serializeUnknown(alloc, payload.$L())";
            default:
                Matcher vector = VECTOR_PATTERN.matcher(paramTypeLower);
                if (vector.matches()) {
                    String innerType = vector.group(1);
                    String specific = "";
                    switch (innerType.toLowerCase()) {
                        case "int":
                        case "long":
                        case "bytes":
                        case "string":
                            specific = Character.toUpperCase(innerType.charAt(0)) + innerType.substring(1);
                            break;
                    }
                    return "serialize" + specific + "Vector(alloc, payload.$L())";
                } else if (paramTypeLower.endsWith("true")) {
                    return null;
                } else if (paramTypeLower.startsWith("flags.")) {
                    return "serializeFlags(alloc, payload.$L())";
                } else {
                    return "serialize(alloc, payload.$L())";
                }
        }
    }

    private TypeName wrapOptional(MethodSpec.Builder attribute, TypeName type, TlParam param) {
        switch (param.type().toLowerCase()) {
            case "bytes":
            case "int128":
            case "int256":
                return ParameterizedTypeName.get(ClassName.get(Optional.class), type);
            default:
                if (param.type().contains("bytes")) {
                    return ParameterizedTypeName.get(ClassName.get(Optional.class), type);
                }

                attribute.addAnnotation(Nullable.class);
                return type.box();
        }
    }

    private String getPackageName(TlSchema schema, String type, boolean method) {
        StringJoiner pckg = new StringJoiner(".");
        pckg.add(getBasePackageName());

        if (method) {
            pckg.add(METHOD_PACKAGE_PREFIX);
        }

        if (!schema.packagePrefix().isEmpty()) {
            pckg.add(schema.packagePrefix());
        }

        int dot = type.lastIndexOf('.');
        if (dot != -1) {
            pckg.add(type.substring(0, dot));
        }

        return pckg.toString();
    }

    private Map<String, List<TlEntityObject>> collectTypeTree(TlSchema schema) {
        return schema.constructors().stream()
                .filter(c -> !ignoredTypes.contains(c.type()) && !primitiveTypes.contains(c.type()))
                .collect(Collectors.groupingBy(c -> getPackageName(schema, c.type(), false)
                        + "." + normalizeName(c.type())));
    }

    private List<ClassName> awareSuperType(String type) {
        List<ClassName> types = new ArrayList<>();

        switch (type) {
            case "UpdateDeleteMessages":
            case "UpdateDeleteChannelMessages":
                types.add(ClassName.get(BASE_PACKAGE, "PtsUpdate"));
            case "UpdateDeleteScheduledMessages":
                types.add(ClassName.get(BASE_PACKAGE, "UpdateDeleteMessagesFields"));
                break;

            case "BaseMessage":
            case "MessageService":
                types.add(ClassName.get(BASE_PACKAGE, "BaseMessageFields"));
                break;

            case "SendMessage":
            case "SendMedia":
                types.add(ClassName.get(BASE_PACKAGE + ".request.messages", "BaseSendMessageRequest"));
                break;

            case "BaseChatPhoto":
            case "BaseUserProfilePhoto":
                types.add(ClassName.get(BASE_PACKAGE, "ChatPhotoFields"));
                break;

            case "UpdateEditMessage":
            case "UpdateEditChannelMessage":
                types.add(ClassName.get(BASE_PACKAGE, "UpdateEditMessageFields"));
                break;

            case "UpdateNewMessage":
            case "UpdateNewChannelMessage":
                types.add(ClassName.get(BASE_PACKAGE, "PtsUpdate"));
            case "UpdateNewScheduledMessage":
                types.add(ClassName.get(BASE_PACKAGE, "UpdateNewMessageFields"));
                break;

            case "UpdatePinnedMessages":
            case "UpdatePinnedChannelMessages":
                types.add(ClassName.get(BASE_PACKAGE, "UpdatePinnedMessagesFields"));
            case "UpdateReadHistoryOutbox":
            case "UpdateWebPage":
            case "UpdateReadMessagesContents":
            case "UpdateChannelWebPage":
            case "UpdateFolderPeers":
                types.add(ClassName.get(BASE_PACKAGE, "PtsUpdate"));
                break;

            case "UpdateNewEncryptedMessage":
            case "UpdateMessagePollVote":
            case "UpdateChatParticipant":
            case "UpdateChannelParticipant":
            case "UpdateBotStopped":
                types.add(ClassName.get(BASE_PACKAGE, "QtsUpdate"));
                break;

            case "BaseDocument":
            case "BaseWebDocument":
            case "WebDocumentNoProxy":
                types.add(ClassName.get(BASE_PACKAGE, "BaseDocumentFields"));
                break;

            case "MsgDetailedInfo":
            case "MsgResendReq":
            case "MsgsAck":
            case "MsgsAllInfo":
            case "MsgsStateInfo":
            case "MsgsStateReq":
                types.add(ClassName.get(RpcMethod.class));
                break;

            default:
                if (type.endsWith("Empty")) {
                    types.add(ClassName.get(EmptyObject.class));
                }
        }

        return types;
    }
}
