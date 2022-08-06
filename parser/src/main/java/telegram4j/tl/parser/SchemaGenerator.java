package telegram4j.tl.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.immutables.value.Value;
import reactor.core.Exceptions;
import reactor.function.TupleUtils;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.*;
import telegram4j.tl.parser.TlTrees.Parameter;
import telegram4j.tl.parser.TlTrees.Scheme;
import telegram4j.tl.parser.TlTrees.Type;

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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static telegram4j.tl.parser.SchemaGeneratorConsts.*;
import static telegram4j.tl.parser.SourceNames.normalizeName;
import static telegram4j.tl.parser.SourceNames.parentPackageName;
import static telegram4j.tl.parser.Strings.camelize;
import static telegram4j.tl.parser.Strings.screamilize;

@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedAnnotationTypes("telegram4j.tl.parser.GenerateSchema")
public class SchemaGenerator extends AbstractProcessor {

    // TODO: add serialization for JsonVALUE in TlSerializer.serialize(...)

    private static final String METHOD_PACKAGE_PREFIX = "request";
    private static final String MTPROTO_PACKAGE_PREFIX = "mtproto";
    private static final String TEMPLATE_PACKAGE_INFO = "package-info.template";
    private static final String BASE_PACKAGE = "telegram4j.tl";
    private static final String API_SCHEMA = "api.json";
    private static final String MTPROTO_SCHEMA = "mtproto.json";
    private static final String INDENT = "\t";

    private final Map<String, Type> computed = new HashMap<>();

    private PackageElement currentElement;
    private Scheme apiSchema;
    private Scheme mtprotoSchema;
    private List<Scheme> schemas;
    private Map<Scheme, Map<String, List<Type>>> typeTree;
    private Map<String, List<Type>> concTypeTree;

    private int iteration;
    private int schemaIteration;
    private Scheme schema;
    private Map<String, List<Type>> currTypeTree;

    // processing resources

    private final Set<String> computedSerializers = new HashSet<>();
    private final Set<String> computedSizeOfs = new HashSet<>();
    private final Set<String> computedDeserializers = new HashSet<>();

    private final List<String> emptyObjects = new ArrayList<>(200);

    private final TypeSpec.Builder serializer = TypeSpec.classBuilder("TlSerializer")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateConstructor);

    private final MethodSpec.Builder sizeOfMethod = MethodSpec.methodBuilder("sizeOf")
            .returns(int.class)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(TlObject.class, "payload")
            .beginControlFlow("switch (payload.identifier())");

    private final MethodSpec.Builder serializeMethod = MethodSpec.methodBuilder("serialize0")
            .returns(ByteBuf.class)
            .addModifiers(Modifier.STATIC)
            .addParameter(ByteBuf.class, "buf")
            .addParameter(TlObject.class, "payload")
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
            // *basic* types
            .addCode("case BOOL_TRUE_ID: return (T) Boolean.TRUE;\n")
            .addCode("case BOOL_FALSE_ID: return (T) Boolean.FALSE;\n")
            .addCode("case VECTOR_ID: return (T) deserializeUnknownVector(payload);\n");

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        try {
            ObjectMapper mapper = new ObjectMapper();

            InputStream api = processingEnv.getFiler().getResource(
                    StandardLocation.ANNOTATION_PROCESSOR_PATH, "", API_SCHEMA).openInputStream();
            InputStream mtproto = processingEnv.getFiler().getResource(
                    StandardLocation.ANNOTATION_PROCESSOR_PATH, "", MTPROTO_SCHEMA).openInputStream();

            apiSchema = mapper.readValue(api, Scheme.class);

            mtprotoSchema = ImmutableTlTrees.Scheme.copyOf(mapper.readValue(mtproto, Scheme.class))
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
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[TL parser] Generation package must be specified once!");
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
                    .mapToInt(c -> c.parameters().size())
                    .sum() == 0;

            if (!isEnum) {
                continue;
            }

            String packageName = parentPackageName(ent.getKey());
            var chunk = ent.getValue();
            for (int i = 0; i < chunk.size(); i++) {
                Type obj = chunk.get(i);

                if (i + 1 == chunk.size()) {
                    String type = normalizeName(obj.type());
                    deserializeMethod.addCode("case 0x$L: return (T) $T.of(identifier);\n",
                            obj.id(), ClassName.get(packageName, type));
                } else {
                    deserializeMethod.addCode("case 0x$L:\n", obj.id());
                }
                sizeOfMethod.addCode("case 0x$L:\n", obj.id());
                serializeMethod.addCode("case 0x$L:\n", obj.id());
            }
        }

        for (int i = 0; i < emptyObjects.size(); i++) {
            String id = emptyObjects.get(i);
            if (i + 1 == emptyObjects.size()) {
                serializeMethod.addCode("case 0x$L: return buf.writeIntLE(payload.identifier());\n", id);
                sizeOfMethod.addCode("case 0x$L: return 4;\n", id);
            } else {
                sizeOfMethod.addCode("case 0x$L:\n", id);
                serializeMethod.addCode("case 0x$L:\n", id);
            }
        }

        var publicSerializeMethod = MethodSpec.methodBuilder("serialize")
                .returns(ByteBuf.class)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ByteBufAllocator.class, "alloc")
                .addParameter(TlObject.class, "payload")
                .addStatement("int size = sizeOf(payload)")
                .addStatement("$T buf = alloc.buffer(size)", ByteBuf.class)
                .addStatement("return serialize0(buf, payload)")
                .build();

        // oh
        sizeOfMethod.addCode("default: throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()) + $S + payload);\n",
                "Incorrect TlObject identifier: 0x", ", payload: ");
        sizeOfMethod.endControlFlow();
        serializeMethod.addCode("default: throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()) + $S + payload);\n",
                "Incorrect TlObject identifier: 0x", ", payload: ");
        serializeMethod.endControlFlow();

        serializer.addMethod(publicSerializeMethod);
        serializer.addMethod(serializeMethod.build());
        serializer.addMethod(sizeOfMethod.build());

        writeTo(JavaFile.builder(getBasePackageName(), serializer.build())
                .addStaticImport(ClassName.get(BASE_PACKAGE, "TlSerialUtil"), "*")
                .addStaticImport(ClassName.get(BASE_PACKAGE, "TlPrimitives"), "*")
                .indent(INDENT)
                .skipJavaLangImports(true)
                .build());

        deserializeMethod.addCode("default: throw new IllegalArgumentException($S + Integer.toHexString(identifier));\n",
                "Incorrect TlObject identifier: 0x");
        deserializeMethod.endControlFlow();
        deserializer.addMethod(deserializeMethod.build());

        writeTo(JavaFile.builder(getBasePackageName(), deserializer.build())
                .addStaticImport(ClassName.get(BASE_PACKAGE, "TlSerialUtil"), "*")
                .addStaticImport(ClassName.get(BASE_PACKAGE, "TlPrimitives"), "*")
                .indent(INDENT)
                .skipJavaLangImports(true)
                .build());
    }

    private void generateMethods() {
        for (Type method : schema.methods()) {
            if (ignoredTypes.contains(method.type())) {
                continue;
            }

            String name = normalizeName(method.name());
            String packageName = getPackageName(schema, method.name(), true);

            boolean singleton = true;
            boolean generic = false;
            boolean hasZeroCopySer = false;
            for (Parameter p : method.parameters()) {
                if (p.type().equals("!X")) generic = true;
                if (!hasZeroCopySer && sizeOfMethod(p.type()) != null) hasZeroCopySer = true;
                if (!p.type().equals("#") && p.type().indexOf('?') == -1) {
                    singleton = false;
                }
            }

            TypeSpec.Builder spec = TypeSpec.interfaceBuilder(name)
                    .addModifiers(Modifier.PUBLIC);

            if (generic) {
                spec.addTypeVariable(genericResultTypeRef);
                spec.addTypeVariable(genericTypeRef.withBounds(wildcardMethodType));
            }

            TypeName returnType = ParameterizedTypeName.get(
                    ClassName.get(TlMethod.class),
                    parseType(method.type()).box());

            spec.addSuperinterfaces(awareSuperType(name));

            spec.addSuperinterface(returnType);
            if (schema.superType() != TlObject.class) {
                spec.addSuperinterface(schema.superType());
            }

            spec.addField(FieldSpec.builder(TypeName.INT, "ID",
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + method.id())
                    .build());

            ClassName immutableTypeRaw = ClassName.get(packageName, "Immutable" + name);
            ClassName immutableTypeBuilderRaw = ClassName.get(packageName, "Immutable" + name, "Builder");
            TypeName immutableBuilderType = generic
                    ? ParameterizedTypeName.get(immutableTypeBuilderRaw, genericResultTypeRef, genericTypeRef)
                    : immutableTypeBuilderRaw;

            MethodSpec.Builder builder = MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(immutableBuilderType)
                    .addCode("return $T.builder();", immutableTypeRaw);

            if (generic) {
                builder.addTypeVariable(genericResultTypeRef);
                builder.addTypeVariable(genericTypeRef.withBounds(wildcardMethodType));
            }

            spec.addMethod(builder.build());

            AnnotationSpec.Builder value = AnnotationSpec.builder(Value.Immutable.class);

            if (singleton) {
                value.addMember("singleton", "true");

                MethodSpec.Builder instance = MethodSpec.methodBuilder("instance")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(immutableTypeRaw)
                        .addCode("return $T.of();", immutableTypeRaw);

                // currently unused
                // if (generic) {
                //     instance.addTypeVariable(genericResultTypeRef);
                //     instance.addTypeVariable(genericTypeRef.withBounds(wildcardMethodType));
                // }

                spec.addMethod(instance.build());
            }

            spec.addAnnotation(value.build());

            spec.addMethod(MethodSpec.methodBuilder("identifier")
                    .addAnnotation(Override.class)
                    .returns(TypeName.INT)
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .addCode("return ID;")
                    .build());

            if (method.parameters().isEmpty()) {
                emptyObjects.add(method.id());
            } else {
                ClassName typeRaw = ClassName.get(packageName, name);
                TypeName payloadType = generic
                        ? ParameterizedTypeName.get(typeRaw, WildcardTypeName.subtypeOf(TypeName.OBJECT),
                        wildcardUnboundedMethodType)
                        : typeRaw;

                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(method.name())), computedSerializers);

                serializeMethod.addCode("case 0x$L: return $L(buf, ($T) payload);\n",
                        method.id(), serializeMethodName, payloadType);

                MethodSpec.Builder serializerBuilder = MethodSpec.methodBuilder(serializeMethodName)
                        .returns(ByteBuf.class)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameters(Arrays.asList(
                                ParameterSpec.builder(ByteBuf.class, "buf").build(),
                                ParameterSpec.builder(payloadType, "payload").build()));

                CodeBlock.Builder sizeOfBlock = CodeBlock.builder();
                CodeBlock.Builder serPrecomputeBlock = CodeBlock.builder(); // variables
                CodeBlock.Builder std = CodeBlock.builder();

                boolean fluentStyle = !hasZeroCopySer;

                int size = 4;
                StringJoiner sizes = new StringJoiner(" + ");
                for (Parameter param : method.parameters()) {
                    String fixedParamName = fixVariableName(param.formattedName());
                    String sizeMethod = sizeOfMethod(param.type());

                    int s = sizeOf(param.type());
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else if (sizeMethod != null) {
                        String sizeVar = param.formattedName() + "Size";

                        sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());
                        sizes.add(sizeVar);
                    }

                    // serialization
                    String ser = serializeMethod(param);
                    if (ser != null) {
                        if (sizeMethod != null) {
                            String var = param.type().equals("string") ? fixedParamName : param.formattedName();
                            std.addStatement(ser, var);
                        } else {
                            String met = byteBufMethod(param);
                            if (fluentStyle) {
                                std.add("\n\t\t." + met + "(" + ser + ")", param.formattedName());
                            } else {
                                std.addStatement("buf." + met + "(" + ser + ")", param.formattedName());
                            }
                        }
                    }

                    TypeName paramType = parseType(param.type());

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC);

                    if (param.type().equals("#")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        String precompute = method.parameters().stream()
                                .filter(p -> p.flagInfo().map(TupleUtils.function((pos, flags, t) ->
                                        flags.equals(param.formattedName()))).orElse(false))
                                .map(f -> {
                                    var flagInfo = f.flagInfo().orElseThrow();
                                    return String.format("(%s()%s ? 1 : 0) << 0x%x", f.formattedName(),
                                            flagInfo.getT3().equals("true") ? "" : " != null", flagInfo.getT1());
                                })
                                .collect(Collectors.joining(" | "));

                        attribute.addCode("return " + precompute + ";");
                    } else if (param.type().endsWith("true")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        attribute.addCode("return false;");
                    } else if (param.type().indexOf('?') != -1) {
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

                if (fluentStyle) {
                    serializerBuilder.addCode("return buf.writeIntLE(payload.identifier())");
                    serializerBuilder.addCode(std.add(";").build());
                } else {
                    serializerBuilder.addStatement("buf.writeIntLE(payload.identifier())");
                    serializerBuilder.addCode(std.build());
                    serializerBuilder.addStatement("return buf");
                }

                serializer.addMethod(serializerBuilder.build());

                if (sizes.length() != 0) {
                    sizeOfBlock.addStatement("return " + size + " + " + sizes);

                    String sizeOfMethodName = uniqueMethodName("sizeOf", name, () ->
                            camelize(parentPackageName(method.name())), computedSizeOfs);

                    sizeOfMethod.addCode("case 0x$L: return $L(($T) payload);\n",
                            method.id(), sizeOfMethodName, payloadType);

                    serializer.addMethod(MethodSpec.methodBuilder(sizeOfMethodName)
                            .returns(int.class)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .addParameter(ParameterSpec.builder(payloadType, "payload").build())
                            .addCode(sizeOfBlock.build())
                            .build());
                } else {
                    sizeOfMethod.addCode("case 0x$L: return $L;\n", method.id(), size);
                }
            }

            writeTo(JavaFile.builder(packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());
        }
    }

    private void generateConstructors() {
        for (Type constructor : schema.constructors()) {
            if (ignoredTypes.contains(constructor.type()) || primitiveTypes.contains(constructor.type())) {
                continue;
            }

            String type = normalizeName(constructor.type());
            String packageName = getPackageName(schema, constructor.type(), false);
            String qualifiedTypeName = packageName + "." + type;

            boolean multiple = currTypeTree.getOrDefault(qualifiedTypeName, List.of()).size() > 1;
            String name = normalizeName(constructor.name());

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
                    .initializer("0x" + constructor.id())
                    .build());

            spec.addMethod(MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.get(packageName, "Immutable" + name, "Builder"))
                    .addCode("return Immutable$L.builder();", name)
                    .build());

            var collect = new LinkedHashSet<>(constructor.parameters());
            collectAttributesRecursive(type, collect);

            var attributes = new ArrayList<>(collect);
            // todo: replace to bit-flags?
            boolean singleton = true;
            int flagsCount = 0;
            boolean firstIsFlags = false;
            boolean hasObjectFields = false;
            for (int i = 0; i < attributes.size(); i++) {
                Parameter p = attributes.get(i);
                if (p.type().equals("#")) {
                    firstIsFlags = i == 0;
                    flagsCount++;
                }
                if (!hasObjectFields && sizeOfMethod(p.type()) != null) hasObjectFields = true;
                if (!p.type().equals("#") && p.type().indexOf('?') == -1) {
                    singleton = false;
                }
            }

            int flagsRemaining = flagsCount;

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

            TypeName typeName = ClassName.get(packageName, "Immutable" + name);
            if (attributes.isEmpty()) {
                deserializeMethod.addCode("case 0x$L: return (T) $T.of();\n", constructor.id(), typeName);

                emptyObjects.add(constructor.id());
            } else {
                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(constructor.name())), computedSerializers);

                String deserializeMethodName = uniqueMethodName("deserialize", name, () ->
                        camelize(parentPackageName(constructor.name())), computedDeserializers);

                TypeName payloadType = ClassName.get(packageName, name);

                serializeMethod.addCode("case 0x$L: return $L(buf, ($T) payload);\n",
                        constructor.id(), serializeMethodName, payloadType);

                deserializeMethod.addCode("case 0x$L: return (T) $L(payload);\n",
                        constructor.id(), deserializeMethodName);

                MethodSpec.Builder deserializerBuilder = MethodSpec.methodBuilder(deserializeMethodName)
                        .returns(typeName)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(ParameterSpec.builder(ByteBuf.class, "payload").build());

                CodeBlock.Builder sizeOfBlock = CodeBlock.builder();
                CodeBlock.Builder serPrecomputeBlock = CodeBlock.builder(); // ByteBuf precomputing
                CodeBlock.Builder std = CodeBlock.builder(); // block with serialization

                if (firstIsFlags) {
                    deserializerBuilder.addStatement("int $L = payload.readIntLE()", attributes.get(0).formattedName());
                    deserializerBuilder.addCode("return $T.builder()", typeName);
                } else if (flagsCount > 0) {
                    deserializerBuilder.addCode("var builder = $T.builder()", typeName);
                } else {
                    deserializerBuilder.addCode("return $T.builder()", typeName);
                }

                boolean fluentStyle = !hasObjectFields;

                int size = 4;
                StringJoiner sizes = new StringJoiner(" + ");
                for (Parameter param : attributes) {
                    String fixedParamName = fixVariableName(param.formattedName());
                    String sizeMethod = sizeOfMethod(param.type());

                    int s = sizeOf(param.type());
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else if (sizeMethod != null) {
                        String sizeVar = param.formattedName() + "Size";

                        sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());
                        sizes.add(sizeVar);
                    }

                    // serialization
                    String ser = serializeMethod(param);
                    if (ser != null) {
                        if (sizeMethod != null) {
                            String var = param.type().equals("string") ? fixedParamName : param.formattedName();
                            std.addStatement(ser, var);
                        } else {
                            String met = byteBufMethod(param);
                            if (fluentStyle) {
                                std.add("\n\t\t." + met + "(" + ser + ")", param.formattedName());
                            } else {
                                std.addStatement("buf." + met + "(" + ser + ")", param.formattedName());
                            }
                        }
                    }

                    if (param.type().equals("#") && !firstIsFlags) {
                        deserializerBuilder.addCode(";\n");
                        deserializerBuilder.addStatement("int $L = payload.readIntLE()", param.formattedName());
                        if (--flagsRemaining == 0) {
                            deserializerBuilder.addCode("return builder.$1L($1L)", param.formattedName());
                        } else {
                            deserializerBuilder.addCode("builder.$1L($1L)", param.formattedName());
                        }
                    } else if (!param.type().equals("#") || firstIsFlags) {
                        deserializerBuilder.addCode("\n\t\t.$L(" + deserializeMethod(param) + ")", param.formattedName());
                    }

                    TypeName paramType = parseType(param.type());

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC);

                    if (param.type().equals("#")) {
                        attribute.addModifiers(Modifier.DEFAULT);
                        String precompute = constructor.parameters().stream()
                                .filter(p -> p.flagInfo().map(TupleUtils.function((pos, flags, t) ->
                                        flags.equals(param.formattedName()))).orElse(false))
                                .map(f -> {
                                    var flagInfo = f.flagInfo().orElseThrow();
                                    return String.format("(%s()%s ? 1 : 0) << 0x%x", f.formattedName(),
                                            flagInfo.getT3().equals("true") ? "" : " != null", flagInfo.getT1());
                                })
                                .collect(Collectors.joining(" | "));

                        attribute.addCode("return " + precompute + ";");
                    } else if (param.type().endsWith("true")) { // adds default false value to boolean flags
                        attribute.addModifiers(Modifier.DEFAULT);
                        attribute.addCode("return false;");
                    } else if (param.type().indexOf('?') != -1) { // optional fields
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
                                ParameterSpec.builder(ByteBuf.class, "buf").build(),
                                ParameterSpec.builder(payloadType, "payload").build()))
                        .addCode(serPrecomputeBlock.build());

                if (fluentStyle) {
                    serializerBuilder.addCode("return buf.writeIntLE(payload.identifier())");
                    serializerBuilder.addCode(std.add(";").build());
                } else {
                    serializerBuilder.addStatement("buf.writeIntLE(payload.identifier())");
                    serializerBuilder.addCode(std.build());
                    serializerBuilder.addStatement("return buf");
                }

                serializer.addMethod(serializerBuilder.build());

                if (sizes.length() != 0) {
                    sizeOfBlock.addStatement("return " + size + " + " + sizes);

                    String sizeOfMethodName = uniqueMethodName("sizeOf", name, () ->
                            camelize(parentPackageName(constructor.name())), computedSizeOfs);

                    sizeOfMethod.addCode("case 0x$L: return $L(($T) payload);\n",
                            constructor.id(), sizeOfMethodName, payloadType);

                    serializer.addMethod(MethodSpec.methodBuilder(sizeOfMethodName)
                            .returns(int.class)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .addParameter(ParameterSpec.builder(payloadType, "payload").build())
                            .addCode(sizeOfBlock.build())
                            .build());
                } else {
                    sizeOfMethod.addCode("case 0x$L: return $L;\n", constructor.id(), size);
                }
            }

            writeTo(JavaFile.builder(packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());

            computed.put(packageName + "." + name, constructor);
        }
    }

    private int sizeOf(String type) {
        switch (type) {
            case "int256":
                return 32;
            case "int128":
                return 16;
            case "long":
            case "double":
                return 8;
            case "int":
            case "Bool":
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
                                        .flatMap(c -> c.parameters().stream())
                                        .filter(p -> e.getValue().stream()
                                                .allMatch(c -> c.parameters().contains(p))),
                                Collectors.toCollection(LinkedHashSet::new))));

        for (var e : superTypes.entrySet()) {
            String name = normalizeName(e.getKey());
            var params = e.getValue(); // common parameters
            String packageName = parentPackageName(e.getKey());
            String qualifiedName = e.getKey();

            boolean canMakeEnum = currTypeTree.get(qualifiedName).stream()
                    .mapToInt(c -> c.parameters().size()).sum() == 0 &&
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

                for (Type constructor : currTypeTree.get(qualifiedName)) {
                    String subtypeName = normalizeName(constructor.name());
                    String constName = screamilize(subtypeName.substring(shortenName.length()));

                    spec.addEnumConstant(constName, TypeSpec.anonymousClassBuilder(
                            "0x$L", constructor.id())
                            .build());

                    ofMethod.addCode("case 0x$L: return $L;\n", constructor.id(), constName);

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

                for (Parameter param : params) {
                    TypeName paramType = parseType(param.type());

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

                    boolean optionalInExt = currTypeTree.getOrDefault(qualifiedName, List.of()).stream()
                            .flatMap(c -> c.parameters().stream())
                            .anyMatch(p -> p.type().indexOf('?') != -1 && p.name().equals(param.name()));

                    if (!param.type().endsWith("true") && (param.type().indexOf('?') != -1 || optionalInExt)) {
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

        for (Type e : apiSchema.constructors()) {
            if (primitiveTypes.contains(e.type())) {
                String name = screamilize(e.name()) + "_ID";

                spec.addField(FieldSpec.builder(int.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("0x" + e.id())
                        .build());
            }
        }

        writeTo(JavaFile.builder(BASE_PACKAGE, spec.build())
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

            Function<Scheme, Set<String>> methodPackagesCollector = schema -> schema.methods().stream()
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

    private TypeName parseType(String type) {
        switch (type.toLowerCase()) {
            case "!x": return genericTypeRef;
            case "x": return genericResultTypeRef;
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
                    String innerTypeRaw = flag.group(3);
                    TypeName t = parseType(innerTypeRaw);
                    return innerTypeRaw.equals("true") ? t : t.box();
                }

                Matcher vector = VECTOR_PATTERN.matcher(type);
                if (vector.matches()) {
                    TypeName templateType = parseType(vector.group(1));
                    return ParameterizedTypeName.get(ClassName.get(List.class), templateType.box());
                }

                String packageName = getPackageName(schema, type, false);
                return ClassName.get(packageName, normalizeName(type));
        }
    }

    private void collectAttributesRecursive(String name, Set<Parameter> params) {
        Type constructor = computed.get(name);
        if (constructor == null || constructor.name().equals(name)) {
            return;
        }
        params.addAll(constructor.parameters());
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

    private String deserializeMethod(Parameter param) {

        var flagInfo = param.flagInfo().orElse(null);
        if (flagInfo != null) {
            int position = flagInfo.getT1();
            String flags = flagInfo.getT2();
            String typeRaw = flagInfo.getT3();

            String pos = Integer.toHexString(1 << position);
            if (typeRaw.equals("true")) {
                return "(" + flags + " & 0x" + pos + ") != 0";
            }

            String innerMethod = deserializeMethod0(null, typeRaw);
            return "(" + flags + " & 0x" + pos + ") != 0 ? " + innerMethod + " : null";
        }

        return deserializeMethod0(param.formattedName(), param.type());
    }

    private String deserializeMethod0(@Nullable String name, String type) {
        switch (type.toLowerCase()) {
            case "#":
                Objects.requireNonNull(name, "name");
                return name;
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

    private String byteBufMethod(Parameter param) {
        switch (param.type()) {
            case "Bool":
            case "#":
            case "int": return "writeIntLE";
            case "long": return "writeLongLE";
            case "double": return "writeDoubleLE";
            case "int128":
            case "int256": return "writeBytes";
            default: throw new IllegalStateException("Unexpected value: " + param.type());
        }
    }

    @Nullable
    private String serializeMethod(Parameter param) {
        switch (param.type()) {
            case "int":
            case "#":
            case "long":
            case "int128":
            case "int256":
            case "double": return "payload.$L()";
            case "Bool": return "payload.$L() ? BOOL_TRUE_ID : BOOL_FALSE_ID";
            case "string": return "serializeString0(buf, payload.$L())";
            case "bytes": return "serializeBytes0(buf, payload.$L())";
            case "JSONValue": return "serializeJsonNode0(buf, payload.$L())";
            case "Object": return "serializeUnknown0(buf, payload.$L())";
            default:
                Matcher vector = VECTOR_PATTERN.matcher(param.type());
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
                    return "serialize" + specific + "Vector0(buf, payload.$L())";
                } else if (param.type().endsWith("true")) {
                    return null;
                } else if (param.type().indexOf('?') != -1) {
                    return "serializeFlags0(buf, payload.$L())";
                } else {
                    return "serialize0(buf, payload.$L())";
                }
        }
    }

    @Nullable
    private String sizeOfMethod(String type) {
        switch (type) {
            case "int":
            case "#":
            case "long":
            case "int128":
            case "int256":
            case "Bool":
            case "double":
                return null;
            case "string":
            case "bytes":
                return "sizeOf0(payload.$L())";
            case "JSONValue": return "sizeOfJsonNode(payload.$L())";
            case "Object": return "sizeOfUnknown(payload.$L())";
            default:
                Matcher vector = VECTOR_PATTERN.matcher(type);
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
                    return "sizeOf" + specific + "Vector(payload.$L())";
                } else if (type.endsWith("true")) {
                    return null;
                } else if (type.indexOf('?') != -1) {
                    return "sizeOfFlags(payload.$L())";
                } else {
                    return "sizeOf(payload.$L())";
                }
        }
    }

    private String uniqueMethodName(String prefix, String base, Supplier<String> fixFunc, Set<String> set) {
        String name = prefix + base;
        if (set.contains(name)) {
            String prx = schema.packagePrefix();
            if (prx.isEmpty()) {
                prx = fixFunc.get();
            }
            char up = prx.charAt(0);
            name = prefix + Character.toUpperCase(up) + prx.substring(1) + base;
        }
        if (!set.add(name)) {
            throw new IllegalStateException("Duplicate method name: " + name);
        }

        return name;
    }

    // it's okay if this just add '$' to the end of the name, it's just the name of the generated variable :)
    private String fixVariableName(String formattedName) {
        switch (formattedName) {
            // used variables:
            case "builder":
            case "payload":
            case "buf":
                return formattedName + "$";
            default: return formattedName;
        }
    }

    private TypeName wrapOptional(MethodSpec.Builder attribute, TypeName type, Parameter param) {
        if (param.type().contains("bytes")) {
            return ParameterizedTypeName.get(ClassName.get(Optional.class), type);
        }

        attribute.addAnnotation(Nullable.class);
        return type.box();
    }

    private String getPackageName(Scheme schema, String type, boolean method) {
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

    private Map<String, List<Type>> collectTypeTree(Scheme schema) {
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
