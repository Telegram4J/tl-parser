package telegram4j.tl.generator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufAllocator;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import telegram4j.tl.api.TlMethod;
import telegram4j.tl.api.TlObject;
import telegram4j.tl.generator.TlProcessing.Configuration;
import telegram4j.tl.generator.TlProcessing.Parameter;
import telegram4j.tl.generator.TlProcessing.Type;
import telegram4j.tl.generator.TlProcessing.TypeNameBase;
import telegram4j.tl.generator.renderer.*;
import telegram4j.tl.parser.TlTrees;

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
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static telegram4j.tl.generator.SchemaGeneratorConsts.*;
import static telegram4j.tl.generator.SchemaGeneratorConsts.Style.*;
import static telegram4j.tl.generator.SourceNames.normalizeName;
import static telegram4j.tl.generator.SourceNames.parentPackageName;
import static telegram4j.tl.generator.Strings.camelize;
import static telegram4j.tl.generator.Strings.screamilize;

@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedAnnotationTypes("telegram4j.tl.generator.GenerateSchema")
public class SchemaGenerator extends AbstractProcessor {

    // TODO: add serialization for JsonVALUE in TlSerializer.serialize(...)

    private final Set<String> computed = new HashSet<>();
    private final Map<Integer, Set<String>> sizeOfGroups = new HashMap<>();

    private ImmutableGenerator immutableGenerator;
    private FileService fileService;

    private PackageElement currentElement;
    private List<Tuple2<TlTrees.Scheme, Configuration>> schemas;
    private Map<TlTrees.Scheme, Map<String, List<Type>>> typeTree;
    private List<Tuple2<Predicate<String>, ClassRef>> superTypes;

    private int iteration;
    private int schemaIteration;
    private TlTrees.Scheme schema;
    private TlTrees.Scheme apiScheme; // for generatePrimitives()
    private Configuration config;
    private Map<String, List<Type>> currTypeTree;

    // processing resources

    private final Set<String> computedSerializers = new HashSet<>();
    private final Set<String> computedSizeOfs = new HashSet<>();
    private final Set<String> computedDeserializers = new HashSet<>();

    private final List<String> emptyObjectsIds = new ArrayList<>(200); // and also enums

    private final TopLevelRenderer serializer = ClassRenderer.create(ClassRef.of(BASE_PACKAGE, "TlSerializer"), ClassRenderer.Kind.CLASS)
            .addStaticImport(BASE_PACKAGE + ".TlSerialUtil.*")
            .addStaticImport(BASE_PACKAGE + ".TlInfo.*")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addConstructor(Modifier.PRIVATE).complete();

    private final MethodRenderer<TopLevelRenderer> sizeOfMethod = serializer.addMethod(int.class, "sizeOf")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(TlObject.class, "payload")
            .beginControlFlow("switch (payload.identifier()) {");

    private final MethodRenderer<TopLevelRenderer> serializeMethod = serializer.addMethod(BYTE_BUF, "serialize0")
            .addModifiers(Modifier.STATIC)
            .addParameter(BYTE_BUF, "buf")
            .addParameter(TlObject.class, "payload")
            .beginControlFlow("switch (payload.identifier()) {");

    private final TopLevelRenderer deserializer = ClassRenderer.create(ClassRef.of(BASE_PACKAGE, "TlDeserializer"), ClassRenderer.Kind.CLASS)
            .addStaticImport(BASE_PACKAGE + ".TlSerialUtil.*")
            .addStaticImport(BASE_PACKAGE + ".TlInfo.*")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addConstructor(Modifier.PRIVATE).complete();

    private final MethodRenderer<TopLevelRenderer> deserializeMethod = deserializer.addMethod(genericTypeRef, "deserialize")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariables(genericTypeRef)
            .addParameter(BYTE_BUF, "payload")
            .addStatement("int identifier = payload.readIntLE()")
            .beginControlFlow("switch (identifier) {")
            // *basic* types
            .addStatement("case BOOL_TRUE_ID: return (T) Boolean.TRUE")
            .addStatement("case BOOL_FALSE_ID: return (T) Boolean.FALSE")
            .addStatement("case VECTOR_ID: return (T) deserializeUnknownVector(payload)");

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        fileService = new FileService(processingEnv.getFiler());
        immutableGenerator = new ImmutableGenerator(fileService, processingEnv.getElementUtils());
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

            String basePackageName = getBasePackageName();

            String annName = GenerateSchema.class.getCanonicalName();
            var ann = currentElement.getAnnotationMirrors().stream()
                    .filter(e -> e.getAnnotationType().toString().equals(annName))
                    .findFirst()
                    .orElseThrow();

            var configs = Configuration.parse(basePackageName, ann);

            schemas = new ArrayList<>(configs.length);
            typeTree = new HashMap<>(configs.length);

            ObjectMapper mapper = new ObjectMapper();

            for (Configuration cfg : configs) {
                try {
                    InputStream is = processingEnv.getFiler().getResource(
                            StandardLocation.ANNOTATION_PROCESSOR_PATH, "",
                            cfg.name + ".json").openInputStream();

                    var schema = mapper.readValue(is, TlTrees.Scheme.class);

                    if (cfg.name.equals("api")) {
                        apiScheme = schema;
                    }

                    typeTree.put(schema, collectTypeTree(cfg, schema));

                    schemas.add(Tuples.of(schema, cfg));
                } catch (Throwable t) {
                    throw Exceptions.propagate(t);
                }
            }

            try {
                mapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);

                InputStream is = processingEnv.getFiler().getResource(
                        StandardLocation.ANNOTATION_PROCESSOR_PATH, "",
                        SUPERTYPES_DATA).openInputStream();

                var map = mapper.readValue(is, new TypeReference<Map<String, List<String>>>() {});
                superTypes = new ArrayList<>(map.size());
                for (var e : map.entrySet()) {
                    String qual = BASE_PACKAGE + '.' + e.getKey();
                    String pck = parentPackageName(qual);
                    ClassRef type = ClassRef.of(pck, qual.substring(pck.length() + 1));

                    Set<String> set = null;
                    Predicate<String> prev = null;
                    for (String s : e.getValue()) {
                        if (s.startsWith("$")) {
                            var patternFilter = Pattern.compile(s.substring(1)).asMatchPredicate();
                            if (prev == null) {
                                prev = patternFilter;
                            } else {
                                prev = prev.or(patternFilter);
                            }
                        } else {
                            if (set == null)
                                set = new HashSet<>();
                            set.add(s);
                        }
                    }

                    Predicate<String> filter = null;
                    if (set != null)
                        filter = set::contains;
                    if (filter == null && prev != null)
                        filter = prev;
                    else if (prev != null)
                        filter = filter.or(prev);

                    if (filter != null)
                        superTypes.add(Tuples.of(filter, type));
                }
            } catch (Throwable t) {
                throw Exceptions.propagate(t);
            }

            preparePackages();
        }

        switch (iteration) {
            case 0: {
                generateInfo();

                var t = schemas.get(schemaIteration);
                schema = t.getT1();
                config = t.getT2();

                currTypeTree = typeTree.get(schema);
                iteration++;
                break;
            }
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
                    var t = schemas.get(schemaIteration);
                    schema = t.getT1();
                    config = t.getT2();

                    currTypeTree = typeTree.get(schema);
                    iteration = 1;
                }
                break;
            case 4:
                generateSerialization();
                iteration++; // end
                break;
        }

        return true;
    }

    private void generateSerialization() {

        for (int i = 0; i < emptyObjectsIds.size(); i++) {
            String id = emptyObjectsIds.get(i);
            if (i + 1 == emptyObjectsIds.size()) {
                serializeMethod.addStatement("case 0x$L: return buf.writeIntLE(payload.identifier())", id);
                sizeOfMethod.addStatement("case 0x$L: return 4", id);
            } else {
                sizeOfMethod.addCode("case 0x$L:", id).ln();
                serializeMethod.addCode("case 0x$L:", id).ln();
            }
        }

        for (var e : sizeOfGroups.entrySet()) {
            for (var it = e.getValue().iterator(); it.hasNext(); ) {
                String s = it.next();
                if (!it.hasNext()) {
                    sizeOfMethod.addStatement("case 0x$L: return $L", s, e.getKey());
                } else {
                    sizeOfMethod.addCode("case 0x$L:", s).ln();
                }
            }
        }

        // oh
        sizeOfMethod.addStatement("default: throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()) + $S + payload)",
                "Incorrect TlObject identifier: 0x", ", payload: ");
        sizeOfMethod.endControlFlow();
        serializeMethod.addStatement("default: throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()) + $S + payload)",
                "Incorrect TlObject identifier: 0x", ", payload: ");
        serializeMethod.endControlFlow();

        sizeOfMethod.complete();

        serializer.addMethod(BYTE_BUF, "serialize")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ByteBufAllocator.class, "alloc")
                .addParameter(TlObject.class, "payload")
                .addStatement("int size = sizeOf(payload)")
                .addStatement("$T buf = alloc.buffer(size)", BYTE_BUF)
                .addStatement("return serialize0(buf, payload)")
                .complete();

        serializeMethod.complete();

        fileService.writeTo(serializer);

        deserializeMethod.addStatement("default: throw new IllegalArgumentException($S + Integer.toHexString(identifier))",
                "Incorrect TlObject identifier: 0x");
        deserializeMethod.endControlFlow();
        deserializeMethod.complete();

        fileService.writeTo(deserializer);
    }

    private void generateMethods() {
        for (var rawMethod : schema.methods()) {
            if (ignoredTypes.contains(rawMethod.type())) {
                continue;
            }

            Type method = Type.parse(config, rawMethod);

            TopLevelRenderer renderer = ClassRenderer.create(
                    ClassRef.of(method.name.packageName, method.name.normalized()),
                            ClassRenderer.Kind.INTERFACE)
                    .addModifiers(Modifier.PUBLIC);

            boolean generic = method.type.rawType.equals("X");
            if (generic) {
                renderer.addTypeVariables(genericResultTypeRef, genericTypeRef.withBounds(wildcardMethodType));
            }

            TypeRef returnType = ParameterizedTypeRef.of(TlMethod.class, mapType(method.type).safeBox());

            String name = method.name.normalized();
            var interfaces = additionalSuperTypes(name);
            renderer.addInterfaces(interfaces);
            if (config.superType != null) {
                renderer.addInterface(config.superType);
            }

            renderer.addInterface(returnType);

            renderer.addField(int.class, "ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + method.id)
                    .complete();

            boolean singleton = true;
            for (Parameter p : method.parameters) {
                if (!p.type.rawType.equals("#") && !p.type.isFlag()) singleton = false;
                if (p.type.isFlag()) {
                    generateBitPosAndMask(p, renderer);
                }
            }

            ClassRef immutableTypeRaw = ClassRef.of(method.name.packageName, "Immutable" + name);
            ClassRef immutableTypeBuilderRaw = immutableTypeRaw.nested("Builder");
            TypeRef immutableBuilderType = generic
                    ? ParameterizedTypeRef.of(immutableTypeBuilderRaw, genericResultTypeRef, genericTypeRef)
                    : immutableTypeBuilderRaw;

            if (!method.parameters.isEmpty()) {
                var builder = renderer.addMethod(immutableBuilderType, "builder")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

                if (generic) {
                    builder.addTypeVariables(genericResultTypeRef, genericTypeRef.withBounds(wildcardMethodType));
                }

                builder.addStatement("return $T.builder()", immutableTypeRaw).complete();
            }

            renderer.addMethod(int.class, "identifier")
                    .addAnnotations(Override.class)
                    .addModifiers(Modifier.DEFAULT)
                    .addStatement("return ID")
                    .complete();

            if (method.parameters.isEmpty()) {
                emptyObjectsIds.add(method.id);
            } else {
                ClassRef typeRaw = ClassRef.of(method.name.packageName, name);
                TypeRef payloadType = generic
                        ? ParameterizedTypeRef.of(typeRaw,
                        WildcardTypeRef.none(), ParameterizedTypeRef.of(TlMethod.class, WildcardTypeRef.none()))
                        : typeRaw;

                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(method.name.rawType)), computedSerializers);

                serializeMethod.addStatement("case 0x$L: return $L(buf, ($T) payload)",
                        method.id, serializeMethodName, payloadType);

                var methodSerializer = serializer.addMethod(BYTE_BUF, serializeMethodName)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(BYTE_BUF, "buf")
                        .addParameter(payloadType, "payload")
                        .addStatement("buf.writeIntLE(payload.identifier())");

                var sizeOfBlock = serializer.createCode().incIndent(2);

                int size = 4;
                StringJoiner sizes = new StringJoiner(" + ");
                for (int i = 0, n = method.parameters.size(); i < n; i++) {
                    Parameter param = method.parameters.get(i);

                    generateAttribute(method, param, renderer);

                    if (param.type.isBitFlag()) {
                        continue;
                    }

                    String sizeMethod = sizeOfMethod(param);

                    int s = sizeOfPrimitive(param.type);
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else if (sizeMethod != null) {
                        String sizeVar = sizeVariable.apply(param.formattedName());

                        sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());
                        sizes.add(sizeVar + "$W");
                    }

                    String ser = serializeMethod(param);
                    if (sizeMethod != null) {
                        methodSerializer.addStatement(ser, param.formattedName());
                    } else {
                        if (param.type.rawType.equals("int128") ||
                                param.type.rawType.equals("int256")) {
                            methodSerializer.addStatement("$1T $2L = payload.$2L()", BYTE_BUF, param.formattedName());
                            methodSerializer.addStatement("buf.writeBytes($1L, $1L.readerIndex(), $1L.readableBytes())", param.formattedName());
                        } else {
                            String met = byteBufMethod(param);
                            methodSerializer.addStatement("buf." + met + "(" + ser + ")", param.formattedName());
                        }
                    }
                }

                methodSerializer.addStatement("return buf");
                methodSerializer.complete();

                if (sizes.length() != 0) {
                    sizeOfBlock.addStatementFormatted("return " + size + " + " + sizes);

                    String sizeOfMethodName = uniqueMethodName("sizeOf", name, () ->
                            camelize(parentPackageName(method.name.rawType)), computedSizeOfs);

                    sizeOfMethod.addCode("case 0x$L: return $L(($T) payload);\n",
                            method.id, sizeOfMethodName, payloadType);

                    serializer.addMethod(int.class, sizeOfMethodName)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .addParameter(payloadType, "payload")
                            .addCode(sizeOfBlock.complete())
                            .complete();
                } else {
                    var group = sizeOfGroups.computeIfAbsent(size, i -> new HashSet<>());
                    group.add(method.id);
                }
            }

            fileService.writeTo(renderer);

            TypeRef superType = config.superType != null
                    ? config.superType : ParameterizedTypeRef.of(TlMethod.class, WildcardTypeRef.none());

            var typeRefs = generic ? List.of(genericResultTypeRef,
                    genericTypeRef.withBounds(wildcardMethodType)) : List.<TypeVariableRef>of();
            immutableGenerator.process(prepareType(method, renderer.name, singleton, typeRefs,
                    interfaces, superType));
        }
    }

    private void generateConstructors() {
        for (var rawConstructor : schema.constructors()) {
            if (ignoredTypes.contains(rawConstructor.type()) || primitiveTypes.contains(rawConstructor.type())) {
                continue;
            }

            Type constructor = Type.parse(config, rawConstructor);

            String name = constructor.name.normalized();
            String type = constructor.type.normalized();
            String packageName = constructor.type.packageName;

            ClassRef typeName = ClassRef.of(packageName, type);
            // Ignore constructor if its type is enum
            if (computed.contains(typeName.qualifiedName())) {
                continue;
            }

            boolean multiple = currTypeTree.getOrDefault(typeName.qualifiedName(), List.of()).size() > 1;

            // add Base* prefix to prevent matching with type name, e.g. SecureValueError
            if (type.equals(name) && multiple) {
                name = "Base" + name;
            } else if (!multiple && !type.equals("Object")) { // use type name if this object type is singleton and type isn't equals Object
                name = type;
            }

            var interfaces = additionalSuperTypes(name);
            var renderer = ClassRenderer.create(ClassRef.of(packageName, name), ClassRenderer.Kind.INTERFACE)
                    .addModifiers(Modifier.PUBLIC)
                    .addInterfaces(interfaces);

            TypeRef superType = multiple ? typeName : config.superType != null ? config.superType : ClassRef.of(TlObject.class);

            renderer.addInterface(superType);

            renderer.addField(int.class, "ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + constructor.id)
                    .complete();

            ClassRef immutableType = renderer.name.peer(immutable.apply(name));

            boolean singleton = true;
            int flagsCount = 0;
            for (Parameter p : constructor.parameters) {
                if (p.type.rawType.equals("#")) flagsCount++;
                if (!p.type.rawType.equals("#") && !p.type.isFlag()) singleton = false;
                if (p.type.isFlag()) {
                    generateBitPosAndMask(p, renderer);
                }
            }

            int flagsRemaining = flagsCount;

            if (!constructor.parameters.isEmpty()) {
                renderer.addMethod(immutableType.nested("Builder"), "builder", Modifier.PUBLIC, Modifier.STATIC)
                        .addStatement("return Immutable$L.builder()", name)
                        .complete();
            }

            renderer.addMethod(int.class, "identifier")
                    .addAnnotations(Override.class)
                    .addModifiers(Modifier.DEFAULT)
                    .addStatement("return ID")
                    .complete();

            if (constructor.parameters.isEmpty()) {
                deserializeMethod.addStatement("case 0x$L: return (T) $T.of()", constructor.id, immutableType);

                emptyObjectsIds.add(constructor.id);
            } else {
                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(constructor.name.rawType)), computedSerializers);

                String deserializeMethodName = uniqueMethodName("deserialize", name, () ->
                        camelize(parentPackageName(constructor.name.rawType)), computedDeserializers);

                serializeMethod.addStatement("case 0x$L: return $L(buf, ($T) payload)",
                        constructor.id, serializeMethodName, renderer.name);

                deserializeMethod.addStatement("case 0x$L: return (T) $L(payload)",
                        constructor.id, deserializeMethodName);

                var typeDeserializer = deserializer.addMethod(immutableType, deserializeMethodName,
                                Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(BYTE_BUF, "payload");

                boolean firstIsFlag = constructor.parameters.get(0).type.rawType.equals("#");
                if (firstIsFlag) {
                    typeDeserializer.addStatement("int $L = payload.readIntLE()", constructor.parameters.get(0).formattedName());
                }

                boolean needSeparation = flagsCount > 1 || flagsCount == 1 && !firstIsFlag;
                if (needSeparation) {
                    typeDeserializer.addCode("var builder = $T.builder()", immutableType).incIndent().ln();
                } else {
                    typeDeserializer.addCode("return $T.builder()", immutableType).incIndent().ln();
                }

                var typeSerializer = serializer.addMethod(BYTE_BUF, serializeMethodName,
                                Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(BYTE_BUF, "buf")
                        .addParameter(renderer.name, "payload")
                        .addStatement("buf.writeIntLE(payload.identifier())");

                var sizeOfBlock = serializer.createCode().incIndent(2);

                int size = 4;
                StringJoiner sizes = new StringJoiner(" + ");
                for (int i = 0, n = constructor.parameters.size(); i < n; i++) {
                    Parameter param = constructor.parameters.get(i);

                    generateAttribute(constructor, param, renderer);

                    if (param.type.isBitFlag()) {
                        continue;
                    }

                    String sizeMethod = sizeOfMethod(param);

                    int s = sizeOfPrimitive(param.type);
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else if (sizeMethod != null) {
                        String sizeVar = sizeVariable.apply(param.formattedName());

                        sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());

                        // TODO: Maybe add an overflow check?
                        sizes.add(sizeVar + "$W");
                    }

                    String ser = serializeMethod(param);
                    if (sizeMethod != null) {
                        typeSerializer.addStatement(ser, param.formattedName());
                    } else {
                        if (param.type.rawType.equals("int128") ||
                            param.type.rawType.equals("int256")) {
                            // the problem is that writeBytes(ByteBuf) changes reader and writer indexes, which is bad for us
                            typeSerializer.addStatement("$1T $2L = payload.$2L()", BYTE_BUF, param.formattedName());
                            typeSerializer.addStatement("buf.writeBytes($1L, $1L.readerIndex(), $1L.readableBytes())",
                                    param.formattedName());
                        } else {
                            String met = byteBufMethod(param);
                            typeSerializer.addStatement("buf." + met + "(" + ser + ")", param.formattedName());
                        }
                    }

                    String deser = deserializeMethod(name, param);
                    if (param.type.rawType.equals("#")) { // TODO: dont write into the separate variable if type have only bit-flags
                        flagsRemaining--;

                        if (i == 0 || flagsCount > 1 && flagsRemaining > 0) {
                            typeDeserializer.addCode(".$1L($1L)", param.formattedName());
                        } else {
                            typeDeserializer.addStatement("int $L = payload.readIntLE()", param.formattedName());

                            if (flagsRemaining == 0) {
                                typeDeserializer.addCode("return builder.$1L($1L)", param.formattedName()).incIndent();
                            } else {
                                typeDeserializer.addCode("builder.$1L($1L)", param.formattedName());
                            }
                        }
                    } else {
                        typeDeserializer.addCode(".$L(" + deser + ")", param.formattedName());
                    }

                    if (flagsRemaining != 0 && needSeparation &&
                            constructor.parameters.subList(i, constructor.parameters.size()).stream()
                                    .anyMatch(p -> p.type.rawType.equals("#"))) {
                        typeDeserializer.decIndent().addCode(';').ln();
                    } else {
                        typeDeserializer.ln();
                    }
                }

                typeSerializer.addStatement("return buf").complete();
                typeDeserializer.addStatement(".build()").decIndent().complete();

                if (sizes.length() != 0) {
                    sizeOfBlock.addStatementFormatted("return " + size + " + " + sizes);

                    String sizeOfMethodName = uniqueMethodName("sizeOf", name, () ->
                            camelize(parentPackageName(constructor.name.rawType)), computedSizeOfs);

                    sizeOfMethod.addStatement("case 0x$L: return $L(($T) payload)",
                            constructor.id, sizeOfMethodName, renderer.name);

                    serializer.addMethod(int.class, sizeOfMethodName, Modifier.PRIVATE, Modifier.STATIC)
                            .addParameter(renderer.name, "payload")
                            .addCode(sizeOfBlock.complete())
                            .complete();
                } else {
                    var group = sizeOfGroups.computeIfAbsent(size, i -> new HashSet<>());
                    group.add(constructor.id);
                }
            }

            fileService.writeTo(renderer);

            immutableGenerator.process(prepareType(constructor, renderer.name, singleton, List.of(),
                    interfaces, superType));
        }
    }

    private ImmutableGenerator.ValueType prepareType(Type tlType, ClassRef baseType, boolean singleton,
                                                     List<TypeVariableRef> typeVars, List<? extends TypeRef> interfaces,
                                                     TypeRef superType) {
        ImmutableGenerator.ValueType valType = new ImmutableGenerator.ValueType(baseType, typeVars, interfaces);
        valType.attributes = new ArrayList<>(tlType.parameters.size());

        NameDeduplicator initBitsName = NameDeduplicator.create("initBits");
        NameDeduplicator hashCodeName = NameDeduplicator.create("h");
        NameDeduplicator equalsName = NameDeduplicator.create("that");

        int primitiveFieldsCount = 0;
        int refFieldsCount = 0;
        for (Parameter p : tlType.parameters) {
            ImmutableGenerator.ValueAttribute valAttr = new ImmutableGenerator.ValueAttribute(p.formattedName());
            valAttr.type = mapType(p.type);

            initBitsName.accept(valAttr.name);
            hashCodeName.accept(valAttr.name);
            equalsName.accept(valAttr.name);

            if (!p.type.isFlag()) {
                if (valAttr.type instanceof PrimitiveTypeRef) {
                    primitiveFieldsCount++;
                } else {
                    refFieldsCount++;
                }
            }

            if (p.type.isFlag()) {
                valAttr.flagsName = p.type.flagsName();
                valAttr.flagMask = bitMask.apply(valAttr.name, Naming.As.SCREMALIZED);

                if (p.type.isBitFlag()) {
                    valAttr.flags.add(ImmutableGenerator.ValueAttribute.Flag.BIT_FLAG);
                } else {
                    valAttr.flags.add(ImmutableGenerator.ValueAttribute.Flag.OPTIONAL);
                }
            } else if (!p.type.rawType.equals("#")) {
                valType.initBitsCount++;
            }

            if (p.type.rawType.equals("#"))
                valAttr.flags.add(ImmutableGenerator.ValueAttribute.Flag.BIT_SET);

            valType.attributes.add(valAttr);
        }

        if (singleton)
            valType.flags.add(ImmutableGenerator.ValueType.Flag.SINGLETON);

        valType.tlType = tlType;
        valType.superType = superType;

        var types = superType instanceof ClassRef
                ? currTypeTree.getOrDefault(((ClassRef) superType).qualifiedName(), List.of())
                : List.<Type>of();

        if (types.size() > 1) {
            valType.superTypeMethodsNames = types.stream()
                    .flatMap(e -> e.parameters.stream())
                    .filter(p -> types.stream()
                            .allMatch(t -> t.parameters.contains(p)))
                    .map(Parameter::formattedName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } else {
            valType.superTypeMethodsNames = Set.of();
        }

        valType.initBitsName = initBitsName.get();
        valType.hashCodeName = hashCodeName.get();
        valType.equalsName = equalsName.get();

        valType.generated = valType.attributes.stream()
                .filter(e -> !e.flags.contains(ImmutableGenerator.ValueAttribute.Flag.BIT_FLAG))
                .collect(Collectors.toList());

        valType.canOmitCopyConstr = primitiveFieldsCount == valType.generated.size();
        valType.needStubParam = refFieldsCount > 0;
        return valType;
    }

    private void generateSuperTypes() {
        var superTypes = currTypeTree.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.flatMapping(e -> e.getValue().stream()
                                        .flatMap(c -> c.parameters.stream())
                                        .filter(p -> e.getValue().stream()
                                                .allMatch(c -> c.parameters.contains(p))),
                                Collectors.toCollection(LinkedHashSet::new))));

        for (var e : superTypes.entrySet()) {
            String qualifiedName = e.getKey();
            String packageName = parentPackageName(e.getKey());
            String name = qualifiedName.substring(packageName.length() + 1);
            var params = e.getValue(); // common parameters

            boolean canMakeEnum = currTypeTree.get(qualifiedName).stream()
                    .mapToInt(c -> c.parameters.size()).sum() == 0;

            ClassRef className = ClassRef.of(packageName, name);
            TopLevelRenderer renderer = ClassRenderer.create(className, canMakeEnum
                    ? ClassRenderer.Kind.ENUM : ClassRenderer.Kind.INTERFACE);

            renderer.addModifiers(Modifier.PUBLIC);
            renderer.addInterface(config.superType != null ? config.superType : TlObject.class);

            if (canMakeEnum) {
                String shortenName = extractEnumName(qualifiedName);

                var ofMethodCode = renderer.createCode().incIndent(3);

                computed.add(qualifiedName);
                var types = currTypeTree.get(qualifiedName);
                for (int i = 0; i < types.size(); i++) {
                    Type constructor = types.get(i);

                    if (i + 1 == types.size()) {
                        deserializeMethod.addStatement("case 0x$L: return (T) $T.of(identifier)", constructor.id, className);
                    } else {
                        deserializeMethod.addCode("case 0x$L:\n", constructor.id);
                    }

                    String subtypeName = constructor.name.normalized();
                    String constName = screamilize(subtypeName.substring(shortenName.length()));

                    renderer.addConstant(constName, "0x" + constructor.id);

                    ofMethodCode.addStatement("case 0x$L: return $L", constructor.id, constName);

                    emptyObjectsIds.add(constructor.id);

                }

                renderer.addField(int.class, "identifier", Modifier.PRIVATE, Modifier.FINAL).complete();

                renderer.addConstructor()
                        .addParameter(int.class, "identifier")
                        .addStatement("this.identifier = identifier")
                        .complete();

                renderer.addMethod(int.class, "identifier")
                        .addAnnotations(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return identifier")
                        .complete();

                renderer.addMethod(className, "of", Modifier.PUBLIC, Modifier.STATIC)
                        .addParameter(int.class, "identifier")
                        .beginControlFlow("switch (identifier) {")
                        .addCode(ofMethodCode.complete())
                        .addStatement("default: throw new IllegalArgumentException($S + Integer.toHexString(identifier))",
                                "Incorrect type identifier: 0x")
                        .endControlFlow()
                        .complete();
            } else {
                for (Parameter param : params) {
                    TypeRef paramType = mapType(param.type);

                    List<ClassRef> anns = List.of();
                    if (!param.type.isBitFlag() && (param.type.isFlag() ||
                            isNullableInSubclasses(qualifiedName, param))) {
                        anns = List.of(ClassRef.of(Nullable.class));
                    }

                    renderer.addMethod(paramType, param.formattedName())
                            .addAnnotations(anns)
                            .complete();
                }
            }

            fileService.writeTo(renderer);
        }
    }

    private void generateInfo() {

        var tlInfo = ClassRenderer.create(ClassRef.of(BASE_PACKAGE, "TlInfo"), ClassRenderer.Kind.CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addConstructor(Modifier.PRIVATE).complete()
                .addField(int.class, "LAYER", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(Integer.toString(LAYER))
                .complete();

        for (var c : apiScheme.constructors()) {
            if (primitiveTypes.contains(c.type())) {
                String name = screamilize(c.name()) + "_ID";

                tlInfo.addField(int.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("0x" + c.id());
            }
        }

        fileService.writeTo(tlInfo);
    }

    private void generateAttribute(Type type, Parameter param, TopLevelRenderer renderer) {
        TypeRef paramType = mapType(param.type);
        var modifiers = EnumSet.noneOf(Modifier.class);
        List<ClassRef> anns = List.of();
        String defaultValue = null;

        if (param.type.rawType.equals("#")) {
            modifiers.add(Modifier.DEFAULT);

            String precompute = type.parameters.stream()
                    .filter(p -> p.type.isFlag() && p.type.flagsName().equals(param.formattedName()))
                    .map(flag -> {
                        String mask = bitMask.apply(flag.formattedName(), Naming.As.SCREMALIZED);
                        return String.format("(%s()%s ? %s : 0)", flag.formattedName(),
                                flag.type.isBitFlag() ? "" : " != null", mask);
                    })
                    .collect(Collectors.joining(" |$W "));

            defaultValue = "return " + precompute;
        } else if (param.type.isBitFlag()) {
            modifiers.add(Modifier.DEFAULT);

            defaultValue = "return false";
        } else if (param.type.isFlag()) {
            anns = List.of(ClassRef.of(Nullable.class));
        }

        var attribute = renderer.addMethod(paramType, param.formattedName())
                .addAnnotations(anns)
                .addModifiers(modifiers);

        if (defaultValue != null) {
            attribute.addStatementFormatted(defaultValue);
        }

        attribute.complete();
    }

    private void generateBitPosAndMask(Parameter param, TopLevelRenderer renderer) {
        int flagPos = param.type.flagPos();

        String posFieldName = bitPos.apply(param.formattedName(), Naming.As.SCREMALIZED);
        renderer.addField(byte.class, posFieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(Integer.toString(flagPos))
                .complete();

        renderer.addField(int.class, bitMask.apply(param.formattedName(), Naming.As.SCREMALIZED),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("1 << " + posFieldName)
                .complete();
    }

    private int sizeOfPrimitive(TlProcessing.TypeName type) {
        switch (type.rawType) {
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
                return -1;
        }
    }

    private boolean isNullableInSubclasses(String qualifiedName, Parameter param) {
        return currTypeTree.getOrDefault(qualifiedName, List.of()).stream()
                .flatMap(c -> c.parameters.stream())
                .anyMatch(p -> p.type.isFlag() &&
                        p.type.innerType().rawType.equals(param.type.rawType) &&
                        p.name.equals(param.name));
    }

    private void preparePackages() {
        try {

            String processingPackageName = getBasePackageName();
            String template = processingEnv.getFiler().getResource(StandardLocation.ANNOTATION_PROCESSOR_PATH,
                            "", TEMPLATE_PACKAGE_INFO).getCharContent(true)
                    .toString();

            var packages = typeTree.values().stream()
                    .flatMap(e -> e.keySet().stream())
                    .map(SourceNames::parentPackageName)
                    .filter(s -> !s.equals(processingPackageName))
                    .collect(Collectors.toSet());

            for (var t : schemas) {
                var scheme = t.getT1();
                Configuration config = t.getT2();

                for (var method : scheme.methods()) {
                    packages.add(TlProcessing.parsePackageName(config, method.name(), true));
                }
            }

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

    private TypeRef mapType(TlProcessing.TypeNameBase type) {
        switch (type.rawType) {
            case "!X": return genericTypeRef;
            case "X": return genericResultTypeRef;
            case "#":
            case "int": return PrimitiveTypeRef.INT;
            case "true":
            case "Bool": return PrimitiveTypeRef.BOOLEAN;
            case "long": return PrimitiveTypeRef.LONG;
            case "double": return PrimitiveTypeRef.DOUBLE;
            case "bytes":
            case "int128":
            case "int256": return BYTE_BUF;
            case "string": return STRING;
            case "Object": return ClassRef.OBJECT;
            case "JSONValue": return ClassRef.of(JsonNode.class);
            default:
                if (type instanceof TlProcessing.TypeName) {
                    TlProcessing.TypeName t = (TlProcessing.TypeName) type;

                    if (t.isFlag()) {
                        TypeNameBase innerType = t.innerType();
                        TypeRef mapped = mapType(innerType);
                        return t.isBitFlag() ? mapped : mapped.safeBox();
                    } else if (t.isVector()) {
                        TypeNameBase innerType = t.innerType();
                        TypeRef mapped = mapType(innerType);
                        return ParameterizedTypeRef.of(LIST, mapped.safeBox());
                    }
                }

                return ClassRef.of(type.packageName, type.normalized());
        }
    }

    private String getBasePackageName() {
        return currentElement.getQualifiedName().toString();
    }

    private String extractEnumName(String type) {
        return currTypeTree.getOrDefault(type, List.of()).stream()
                .map(c -> c.name.normalized())
                .reduce(Strings::findCommonPart)
                .orElseGet(() -> normalizeName(type));
    }

    private String deserializeMethod(String typeName, Parameter param) {
        if (param.type.rawType.equals("#")) {
            return param.formattedName();
        }

        if (param.type.isFlag()) {
            String flagsName = param.type.flagsName();
            TypeNameBase innerType = param.type.innerType();

            // The immutable object is already in the TlDeserializer imports
            String mask = immutable.apply(typeName) + '.' + bitMask.apply(param.formattedName(), Naming.As.SCREMALIZED);
            String innerMethod = deserializeMethod0(innerType);
            return "(" + flagsName + " & " + mask + ") != 0 ? " + innerMethod + " : null";
        }

        return deserializeMethod0(param.type);
    }

    private String deserializeMethod0(TypeNameBase type) {
        switch (type.rawType) {
            case "Bool": return "deserializeBoolean(payload)";
            case "int": return "payload.readIntLE()";
            case "long": return "payload.readLongLE()";
            case "double": return "payload.readDoubleLE()";
            case "bytes": return "deserializeBytes(payload)";
            case "string": return "deserializeString(payload)";
            case "int128": return "readInt128(payload)";
            case "int256": return "readInt256(payload)";
            case "JSONValue": return "deserializeJsonNode(payload)";
            default:
                if (type instanceof TlProcessing.TypeName) {
                    TlProcessing.TypeName t = (TlProcessing.TypeName) type;

                    if (t.isFlag()) {
                        throw new IllegalStateException("Unexpected flag type: " + type);
                    } else if (t.isVector()) {
                        String innerTypeRaw = t.innerType().rawType;
                        String specific = "";
                        switch (innerTypeRaw) {
                            case "int":
                            case "long":
                            case "bytes":
                            case "string":
                                specific = Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                                break;
                        }

                        // NOTE: bare vectors (msg_container, future_salts)
                        if (t.rawType.contains("%")) {
                            return "deserializeVector0(payload, true, TlDeserializer::deserializeMessage)";
                        } else if (t.rawType.contains("future_salt")) {
                            return "deserializeVector0(payload, true, TlDeserializer::deserializeFutureSalt)";
                        } else {
                            return "deserialize" + specific + "Vector(payload)";
                        }
                    }
                }
                return "deserialize(payload)";
        }
    }

    private String byteBufMethod(Parameter param) {
        switch (param.type.rawType) {
            case "Bool":
            case "#":
            case "int": return "writeIntLE";
            case "long": return "writeLongLE";
            case "double": return "writeDoubleLE";
            default: throw new IllegalStateException("Unexpected value: " + param.type.rawType);
        }
    }

    private String serializeMethod(Parameter param) {
        switch (param.type.rawType) {
            case "int":
            case "#":
            case "long":
            case "int128": // handled manually
            case "int256": // ^
            case "double": return "payload.$L()";
            case "Bool": return "payload.$L() ? BOOL_TRUE_ID : BOOL_FALSE_ID";
            case "string": return "serializeString0(buf, payload.$L())";
            case "bytes": return "serializeBytes0(buf, payload.$L())";
            case "JSONValue": return "serializeJsonNode0(buf, payload.$L())";
            case "Object": return "serializeUnknown0(buf, payload.$L())";
            default:
                if (param.type.isVector()) {
                    String innerTypeRaw = param.type.innerType().rawType;
                    String specific = "";
                    switch (innerTypeRaw.toLowerCase()) {
                        case "int":
                        case "long":
                        case "bytes":
                        case "string":
                            specific = Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                            break;
                    }
                    return "serialize" + specific + "Vector0(buf, payload.$L())";
                } else if (param.type.isFlag()) {
                    return "serializeFlags0(buf, payload.$L())";
                } else {
                    return "serialize0(buf, payload.$L())";
                }
        }
    }

    @Nullable
    private String sizeOfMethod(Parameter param) {
        switch (param.type.rawType) {
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
                if (param.type.isVector()) {
                    String innerTypeRaw = param.type.innerType().rawType;
                    String specific = "";
                    switch (innerTypeRaw) {
                        case "int":
                        case "long":
                        case "bytes":
                        case "string":
                            specific = Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                            break;
                    }
                    return "sizeOf" + specific + "Vector(payload.$L())";
                } else if (param.type.isBitFlag()) {
                    return null;
                } else if (param.type.isFlag()) {
                    return "sizeOfFlags(payload.$L())";
                } else {
                    return "sizeOf(payload.$L())";
                }
        }
    }

    private String uniqueMethodName(String prefix, String base, Supplier<String> fixFunc, Set<String> set) {
        String name = prefix + base;
        if (set.contains(name)) {
            String prx = config.packagePrefix;
            if (prx == null) {
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

    private Map<String, List<Type>> collectTypeTree(Configuration config, TlTrees.Scheme schema) {
        return schema.constructors().stream()
                .filter(c -> !ignoredTypes.contains(c.type()) && !primitiveTypes.contains(c.type()))
                .map(e -> Type.parse(config, e))
                .collect(Collectors.groupingBy(c -> c.type.packageName + '.' + c.type.normalized()));
    }

    private List<ClassRef> additionalSuperTypes(String name) {
        return superTypes.stream()
                .filter(t -> t.getT1().test(name))
                .map(Tuple2::getT2)
                .collect(Collectors.toList());
    }
}
