/*
 * Copyright 2023 Telegram4J
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package telegram4j.tl.generator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufAllocator;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static telegram4j.tl.generator.SchemaGeneratorConsts.*;
import static telegram4j.tl.generator.SchemaGeneratorConsts.Style.*;
import static telegram4j.tl.generator.SourceNames.normalizeName;
import static telegram4j.tl.generator.SourceNames.parentPackageName;
import static telegram4j.tl.generator.Strings.camelize;
import static telegram4j.tl.generator.Strings.screamilize;

@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes("telegram4j.tl.generator.GenerateSchema")
public class SchemaGenerator extends AbstractProcessor {

    private final Set<String> computedEnums = new HashSet<>();
    private final Map<Integer, Set<String>> sizeOfGroups = new HashMap<>();

    private ImmutableGenerator immutableGenerator;
    private FileService fileService;

    private PackageElement currentElement;
    private List<Tuple2<TlTrees.Scheme, Configuration>> schemas;
    private Map<TlTrees.Scheme, Map<String, List<Type>>> typeTree;

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

    private final List<String> emptyObjectsIds = new ArrayList<>(200);

    private final TopLevelRenderer tlInfo = ClassRenderer.create(ClassRef.of(BASE_PACKAGE, "TlInfo"), ClassRenderer.Kind.CLASS)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

    private final MethodRenderer<TopLevelRenderer> tlTypeOf = tlInfo.addMethod(
            ParameterizedTypeRef.of(Class.class, WildcardTypeRef.subtypeOf(TL_OBJECT)), "typeOf")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(int.class, "id")
            .beginControlFlow("return switch (id) {");

    private final TopLevelRenderer serializer = ClassRenderer.create(ClassRef.of(BASE_PACKAGE, "TlSerializer"), ClassRenderer.Kind.CLASS)
            .addStaticImport(BASE_PACKAGE + ".TlSerialUtil.*")
            .addStaticImport(BASE_PACKAGE + ".TlInfo.*")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addConstructor(Modifier.PRIVATE).complete();

    private final MethodRenderer<TopLevelRenderer> sizeOfMethod = serializer.addMethod(int.class, "sizeOf")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(TL_OBJECT, "payload")
            .beginControlFlow("return switch (payload.identifier()) {");

    private final MethodRenderer<TopLevelRenderer> serializeMethod = serializer.addMethod(BYTE_BUF, "serialize")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(BYTE_BUF, "buf")
            .addParameter(TL_OBJECT, "payload")
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
            .beginControlFlow("return (T) switch (identifier) {")
            // *basic* types
            .addStatement("case BOOL_TRUE_ID -> Boolean.TRUE")
            .addStatement("case BOOL_FALSE_ID -> Boolean.FALSE")
            .addStatement("case VECTOR_ID -> deserializeUnknownVector(payload)");

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        fileService = new FileService(processingEnv.getFiler());
        immutableGenerator = new ImmutableGenerator(fileService);
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

            tlInfo.addField(int.class, "LAYER", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer(apiScheme.version())
                    .complete();

            preparePackages();
        }

        switch (iteration) {
            case 0 -> {
                generateInfo();

                var t = schemas.get(schemaIteration);
                schema = t.getT1();
                config = t.getT2();

                currTypeTree = typeTree.get(schema);
                iteration++;
            }
            case 1 -> {
                generateSuperTypes();
                iteration++;
            }
            case 2 -> {
                generateConstructors();
                iteration++;
            }
            case 3 -> {
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
            }
            case 4 -> {
                generateSerialization();
                iteration++; // end
            }
        }

        return true;
    }

    private void generateSerialization() {

        for (int i = 0; i < emptyObjectsIds.size(); i++) {
            String id = emptyObjectsIds.get(i);

            if (i == 0) {
                serializeMethod.addCode("case ");
                sizeOfMethod.addCode("case ");
            }
            serializeMethod.addCode("0x" + id);
            sizeOfMethod.addCode("0x" + id);
            if (i + 1 < emptyObjectsIds.size()) {
                serializeMethod.addCodeFormatted(",$W ");
                sizeOfMethod.addCodeFormatted(",$W ");
            } else {
                serializeMethod.addCode(" -> buf.writeIntLE(payload.identifier());").ln();
                sizeOfMethod.addCode(" -> 4;").ln();
            }
        }

        for (var e : sizeOfGroups.entrySet()) {
            int i = 0;
            for (var it = e.getValue().iterator(); it.hasNext(); ) {
                String s = it.next();
                if (i == 0) {
                    sizeOfMethod.addCode("case ");
                }
                sizeOfMethod.addCode("0x" + s);
                if (!it.hasNext()) {
                    sizeOfMethod.addCode(" -> $L;", e.getKey()).ln();
                } else {
                    sizeOfMethod.addCodeFormatted(",$W ");
                }
                i++;
            }
        }

        sizeOfMethod.addStatement("default -> throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()) + $S + payload)",
                "Incorrect TlObject identifier: 0x", ", payload: ");
        sizeOfMethod.endControlFlow("};");
        serializeMethod.addStatement("default -> throw new IllegalArgumentException($S + Integer.toHexString(payload.identifier()) + $S + payload)",
                "Incorrect TlObject identifier: 0x", ", payload: ");
        serializeMethod.endControlFlow();
        serializeMethod.addStatement("return buf");

        sizeOfMethod.complete();

        serializer.addMethod(BYTE_BUF, "serialize")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ByteBufAllocator.class, "alloc")
                .addParameter(TL_OBJECT, "payload")
                .addStatement("int size = sizeOf(payload)")
                .addStatement("$T buf = alloc.buffer(size)", BYTE_BUF)
                .addStatement("return serialize(buf, payload)")
                .complete();

        serializeMethod.complete();

        fileService.writeTo(serializer);

        deserializeMethod.addStatement("default -> throw new IllegalArgumentException($S + Integer.toHexString(identifier))",
                "Incorrect TlObject identifier: 0x");
        deserializeMethod.endControlFlow("};");
        deserializeMethod.complete();

        fileService.writeTo(deserializer);

        tlInfo.addConstructor(Modifier.PRIVATE).complete();

        tlTypeOf.addStatement("default -> throw new IllegalArgumentException($S + Integer.toHexString(id))",
                "Incorrect TlObject identifier: 0x");
        tlTypeOf.endControlFlow("};");
        tlTypeOf.complete();

        fileService.writeTo(tlInfo);
    }

    private void generateMethods() {
        for (var rawMethod : schema.methods()) {
            if (ignoredTypes.contains(rawMethod.type())) {
                continue;
            }

            Type method = Type.parse(config, rawMethod);

            String name = method.name.normalized();
            ClassRef immutableTypeRaw = ClassRef.of(method.name.packageName, immutable.apply(name));
            boolean isEmptyMethod = method.parameters.isEmpty();
            TopLevelRenderer renderer = ClassRenderer.create(
                    ClassRef.of(method.name.packageName, name),
                            isEmptyMethod ? ClassRenderer.Kind.CLASS : ClassRenderer.Kind.INTERFACE)
                    .addModifiers(Modifier.PUBLIC);

            if (isEmptyMethod) {
                renderer.addModifiers(Modifier.FINAL);
            }

            boolean generic = method.type.rawType.equals("X");
            if (generic) {
                // <R, T extends TlMethod<? extends R>>
                renderer.addTypeVariables(genericResultTypeRef, genericTypeRef.withBounds(wildcardMethodType));
            }

            TypeRef returnType = ParameterizedTypeRef.of(TL_METHOD, mapType(method.type).safeBox());

            renderer.addInterfaces(additionalSuperTypes(name));
            if (config.superType != TL_OBJECT) {
                renderer.addInterface(config.superType);
            }

            renderer.addInterface(returnType);

            var idConst = renderer.addField(int.class, "ID");
            if (isEmptyMethod) {
                idConst.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            }
            idConst.initializer("0x" + method.id).complete();

            boolean singleton = true;
            for (Parameter p : method.parameters) {
                if (p.type.isFlag()) {
                    generateBitPosAndMask(p, renderer);
                } else if (!p.type.isBitSet()) {
                    singleton = false;
                }
            }

            ClassRef immutableTypeBuilderRaw = immutableTypeRaw.nested("Builder");
            TypeRef immutableBuilderType = generic
                    ? ParameterizedTypeRef.of(immutableTypeBuilderRaw, genericResultTypeRef, genericTypeRef)
                    : immutableTypeBuilderRaw;

            if (!isEmptyMethod) {
                var builder = renderer.addMethod(immutableBuilderType, "builder")
                        .addModifiers(Modifier.STATIC);

                if (generic) {
                    builder.addTypeVariables(genericResultTypeRef, genericTypeRef.withBounds(wildcardMethodType));
                }

                builder.addStatement("return $T.builder()", immutableTypeRaw).complete();
            } else {
                renderer.addField(renderer.name, "INSTANCE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", renderer.name)
                        .complete();

                renderer.addConstructor(Modifier.PRIVATE).complete();

                renderer.addMethod(renderer.name, "instance", Modifier.PUBLIC, Modifier.STATIC)
                        .addStatement("return INSTANCE")
                        .complete();
            }

            var identifierMethod = renderer.addMethod(int.class, "identifier")
                    .addAnnotation(Override.class);
            if (isEmptyMethod) {
                identifierMethod.addModifiers(Modifier.PUBLIC);
            } else {
                identifierMethod.addModifiers(Modifier.DEFAULT);
            }

            identifierMethod.addStatement("return ID");
            identifierMethod.complete();

            if (isEmptyMethod) {
                emptyObjectsIds.add(method.id);

                renderer.addMethod(int.class, "hashCode")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return ID")
                        .complete();

                renderer.addMethod(String.class, "toString")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return \"$L#$L{}\"", renderer.name, method.id)
                        .complete();
            } else {
                ClassRef typeRaw = ClassRef.of(method.name.packageName, name);
                TypeRef payloadType = generic
                        ? ParameterizedTypeRef.of(typeRaw,
                        WildcardTypeRef.none(), ParameterizedTypeRef.of(TL_METHOD, WildcardTypeRef.none()))
                        : typeRaw;

                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(method.name.rawType)), computedSerializers);

                serializeMethod.addStatement("case 0x$L -> $L(buf, ($T) payload)",
                        method.id, serializeMethodName, payloadType);

                var methodSerializer = serializer.addMethod(PrimitiveTypeRef.VOID, serializeMethodName)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(BYTE_BUF, "buf")
                        .addParameter(payloadType, "payload")
                        .addStatement("buf.writeIntLE(payload.identifier())");

                var sizeOfBlock = serializer.createCode().incIndent(2);

                int size = 4;
                StringJoiner sizes = new StringJoiner(" +$W ");
                for (int i = 0, n = method.parameters.size(); i < n; i++) {
                    Parameter param = method.parameters.get(i);

                    generateAttribute(param, renderer);

                    if (param.type.isBitFlag()) {
                        continue;
                    }

                    int s = sizeOfPrimitive(param.type);
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else {
                        writeSizeOfVariable(sizeOfBlock, sizes, param);
                    }

                    writeSerializeMethod(methodSerializer, param);
                }

                methodSerializer.complete();

                if (sizes.length() != 0) {
                    sizeOfBlock.addStatementFormatted("return " + size + " + " + sizes);

                    String sizeOfMethodName = uniqueMethodName("sizeOf", name, () ->
                            camelize(parentPackageName(method.name.rawType)), computedSizeOfs);

                    sizeOfMethod.addStatement("case 0x$L -> $L(($T) payload)", method.id, sizeOfMethodName, payloadType);

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

            tlTypeOf.addStatement("case 0x$L -> $T.class", method.id, renderer.name);

            if (isEmptyMethod) {
                continue;
            }

            TypeRef superType = config.superType != TL_OBJECT
                    ? config.superType
                    : ParameterizedTypeRef.of(TL_METHOD, WildcardTypeRef.none());

            var typeRefs = generic
                    ? List.of(genericResultTypeRef, genericTypeRef.withBounds(wildcardMethodType))
                    : List.<TypeVariableRef>of();

            immutableGenerator.process(prepareType(method, renderer.name, singleton, typeRefs, superType));
        }
    }

    private void writeSizeOfVariable(CodeRenderer<CharSequence> sizeOfBlock, StringJoiner sizes, Parameter param) {
        String sizeVar = sizeVariable.apply(param.formattedName());

        if (param.type.isFlag()) {
            int primitiveSizeof = sizeOfPrimitive(param.type.innerType());
            if (primitiveSizeof != -1) {
                sizeOfBlock.addStatement("int $L = payload.$L() != null ? $L : 0", sizeVar, param.formattedName(), primitiveSizeof);
            } else {
                sizeOfBlock.addStatement("var $1L = payload.$1L()", param.formattedName());
                String sizeMethod = sizeOfMethod(param.type.innerType());
                sizeOfBlock.addStatement("int $L = $L != null ? " + sizeMethod + " : 0",
                        sizeVar, param.formattedName(), param.formattedName());
            }
        } else {
            String sizeMethod = sizeOfMethod(param);
            sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());
        }

        sizes.add(sizeVar);
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
            if (computedEnums.contains(typeName.qualifiedName())) {
                continue;
            }

            boolean multiple = currTypeTree.getOrDefault(typeName.qualifiedName(), List.of()).size() > 1;

            // add Base* prefix to prevent matching with type name
            if (type.equals(name) && multiple) {
                name = "Base" + name;
            } else if (!multiple && !type.equals("Object")) { // use type name if this object type is singleton and type isn't equals Object
                name = type;
            }

            boolean isEmptyObject = constructor.parameters.isEmpty();
            var renderer = ClassRenderer.create(ClassRef.of(packageName, name),
                            isEmptyObject ? ClassRenderer.Kind.CLASS : ClassRenderer.Kind.INTERFACE)
                    .addModifiers(Modifier.PUBLIC);

            if (isEmptyObject) {
                renderer.addModifiers(Modifier.FINAL);
            } else if (multiple) {
                renderer.addModifiers(Modifier.NON_SEALED);
            }

            renderer.addInterfaces(additionalSuperTypes(name));

            ClassRef immutableType = renderer.name.peer(immutable.apply(name));
            TypeRef superType = multiple ? typeName : config.superType != TL_OBJECT ? config.superType : TL_OBJECT;

            renderer.addInterface(superType);

            var idConst = renderer.addField(int.class, "ID");
            if (isEmptyObject) {
                idConst.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            }
            idConst.initializer("0x" + constructor.id).complete();

            boolean singleton = true;
            // if true then will be generated simplified deserialization
            // which directly pass bit sets into the builder
            boolean noValueFlags = true;
            for (Parameter p : constructor.parameters) {
                if (p.type.isFlag()) {
                    generateBitPosAndMask(p, renderer);
                } else if (!p.type.isBitSet()) {
                    singleton = false;
                }

                if (!p.type.isBitSet() && !p.type.isBitFlag()) {
                    noValueFlags = false;
                }
            }

            if (!isEmptyObject) {
                renderer.addMethod(immutableType.nested("Builder"), "builder", Modifier.STATIC)
                        .addStatement("return Immutable$L.builder()", name)
                        .complete();
            } else {
                renderer.addField(renderer.name, "INSTANCE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", renderer.name)
                        .complete();

                renderer.addConstructor(Modifier.PRIVATE).complete();

                renderer.addMethod(renderer.name, "instance", Modifier.PUBLIC, Modifier.STATIC)
                        .addStatement("return INSTANCE")
                        .complete();
            }

            var identifierMethod = renderer.addMethod(int.class, "identifier")
                    .addAnnotation(Override.class);
            if (isEmptyObject) {
                identifierMethod.addModifiers(Modifier.PUBLIC);
            } else {
                identifierMethod.addModifiers(Modifier.DEFAULT);
            }

            identifierMethod.addStatement("return ID");
            identifierMethod.complete();

            if (isEmptyObject) {
                deserializeMethod.addStatement("case 0x$L -> $T.instance()", constructor.id, renderer.name);

                emptyObjectsIds.add(constructor.id);

                renderer.addMethod(int.class, "hashCode")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return ID")
                        .complete();

                renderer.addMethod(String.class, "toString")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return \"$L#$L{}\"", renderer.name, constructor.id)
                        .complete();
            } else {
                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(constructor.name.rawType)), computedSerializers);

                String deserializeMethodName = uniqueMethodName("deserialize", name, () ->
                        camelize(parentPackageName(constructor.name.rawType)), computedDeserializers);

                serializeMethod.addStatement("case 0x$L -> $L(buf, ($T) payload)",
                        constructor.id, serializeMethodName, renderer.name);

                deserializeMethod.addStatement("case 0x$L -> $L(payload)",
                        constructor.id, deserializeMethodName);

                var typeDeserializer = deserializer.addMethod(immutableType, deserializeMethodName,
                                Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(BYTE_BUF, "payload");

                if (!noValueFlags) {
                    for (int i = 0, n = constructor.parameters.size(); i < n; i++) {
                        Parameter param = constructor.parameters.get(i);
                        if (param.type.isBitSet()) {
                            Parameter prev;
                            if (i == 0 || (prev = constructor.parameters.get(i - 1)).type.isBitFlag() || prev.type.isBitSet()) {
                                typeDeserializer.addStatement("int $L = payload.readIntLE()", param.formattedName());
                            } else {
                                typeDeserializer.addStatement("int $L", param.formattedName());
                            }
                        }
                    }
                }

                typeDeserializer.addCode("return $T.builder()", immutableType).incIndent().ln();

                var typeSerializer = serializer.addMethod(PrimitiveTypeRef.VOID, serializeMethodName,
                                Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(BYTE_BUF, "buf")
                        .addParameter(renderer.name, "payload")
                        .addStatement("buf.writeIntLE(payload.identifier())");

                var sizeOfBlock = serializer.createCode().incIndent(2);

                int size = 4;
                StringJoiner sizes = new StringJoiner(" +$W ");
                for (int i = 0, n = constructor.parameters.size(); i < n; i++) {
                    Parameter param = constructor.parameters.get(i);

                    generateAttribute(param, renderer);

                    if (param.type.isBitFlag()) {
                        continue;
                    }

                    int s = sizeOfPrimitive(param.type);
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else {
                        writeSizeOfVariable(sizeOfBlock, sizes, param);
                    }

                    writeSerializeMethod(typeSerializer, param);

                    if (param.type.isBitSet()) {
                        Parameter prev;
                        if (noValueFlags) {
                            typeDeserializer.addCode(".$1L(payload.readIntLE())", param.formattedName());
                        } else if (i == 0 || (prev = constructor.parameters.get(i - 1)).type.isBitFlag() || prev.type.isBitSet()) {
                            typeDeserializer.addCode(".$1L($1L)", param.formattedName());
                        } else {
                            typeDeserializer.addCode(".$1L($1L = payload.readIntLE())", param.formattedName());
                        }
                    } else {
                        String deser = deserializeMethod(name, param);
                        typeDeserializer.addCode(".$L(" + deser + ")", param.formattedName());
                    }

                    typeDeserializer.ln();
                }

                typeSerializer.complete();
                typeDeserializer.addStatement(".build()").decIndent().complete();

                if (sizes.length() != 0) {
                    sizeOfBlock.addStatementFormatted("return " + size + " + " + sizes);

                    String sizeOfMethodName = uniqueMethodName("sizeOf", name, () ->
                            camelize(parentPackageName(constructor.name.rawType)), computedSizeOfs);

                    sizeOfMethod.addStatement("case 0x$L -> $L(($T) payload)",
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

            tlTypeOf.addStatement("case 0x$L -> $T.class", constructor.id, renderer.name);

            if (isEmptyObject)
                continue;

            immutableGenerator.process(prepareType(constructor, renderer.name, singleton, List.of(), superType));
        }
    }

    private ValueType prepareType(Type tlType, ClassRef baseType, boolean singleton,
                                  List<TypeVariableRef> typeVars, TypeRef superType) {
        ValueType valType = new ValueType(baseType, typeVars);
        valType.attributes = new ArrayList<>(tlType.parameters.size());

        NameDeduplicator initBitsName = NameDeduplicator.create("initBits");

        short primitivefCount = 0; // including fields with '#' type
        short reffCount = 0;
        short flagsfCount = 0;
        short bitSetfCount = 0;

        for (Parameter p : tlType.parameters) {
            ValueAttribute valAttr = new ValueAttribute(p.formattedName());
            valAttr.jsonName = SourceNames.jacksonName(p.name);
            valAttr.type = mapType(p.type);

            initBitsName.accept(valAttr.name);

            if (!p.type.isFlag()) {
                if (valAttr.type instanceof PrimitiveTypeRef) {
                    primitivefCount++;
                } else {
                    reffCount++;
                }
            }

            if (p.type.rawType.equals("int128")) {
                valAttr.maxSize = 16;
            } else if (p.type.rawType.equals("int256")) {
                valAttr.maxSize = 32;
            }

            if (p.type.isFlag()) {
                valAttr.flagsName = p.type.flagsName();
                valAttr.flagMask = bitMask.apply(valAttr.name, Naming.As.SCREMALIZED);
                valAttr.flagPos = p.type.flagPos();

                var bitSet = valType.bitSets.computeIfAbsent(valAttr.flagsName, k -> new ValueType.BitSetInfo());
                if (p.type.isBitFlag()) {
                    flagsfCount++;
                    bitSet.bitFlagsCount++;
                    valAttr.flags.add(ValueAttribute.Flag.BIT_FLAG);
                } else {
                    valAttr.flags.add(ValueAttribute.Flag.OPTIONAL);
                    bitSet.valuesMask.add(valAttr.flagMask);
                    bitSet.bitUsage.computeIfAbsent(valAttr.flagPos, k -> new Counter()).value++;
                }
            } else if (!p.type.isBitSet()) {
                valType.initBitsCount++;
            } else {
                bitSetfCount++;
                valAttr.flags.add(ValueAttribute.Flag.BIT_SET);
            }

            valType.attributes.add(valAttr);
        }

        if (singleton)
            valType.flags.add(ValueType.Flag.SINGLETON);

        valType.identifier = tlType.id;
        valType.superType = superType;

        var types = superType instanceof ClassRef
                ? currTypeTree.getOrDefault(((ClassRef) superType).qualifiedName(), List.of())
                : List.<Type>of();

        if (types.size() > 1) {
            valType.superTypeMethodsNames = List.copyOf(types.stream()
                    .flatMap(e -> e.parameters.stream())
                    .filter(e -> !e.type.isBitSet())
                    .filter(p -> types.stream()
                            .allMatch(t -> t.parameters.contains(p)))
                    .map(Parameter::formattedName)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        } else {
            valType.superTypeMethodsNames = List.of();
        }

        valType.initBitsName = initBitsName.get();

        valType.generated = valType.attributes.stream()
                .filter(e -> !e.flags.contains(ValueAttribute.Flag.BIT_FLAG))
                .collect(Collectors.toList());

        if (primitivefCount == valType.generated.size())
            valType.flags.add(ValueType.Flag.CAN_OMIT_COPY_CONSTRUCTOR);

        if (bitSetfCount != 0 && flagsfCount == 0 && primitivefCount - bitSetfCount + reffCount == 0)
            valType.flags.add(ValueType.Flag.CAN_OMIT_OF_METHOD);
        if (reffCount > 0)
            valType.flags.add(ValueType.Flag.NEED_STUB_PARAM);

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
            if (!canMakeEnum) {
                renderer.addModifiers(Modifier.SEALED);
            }

            renderer.addInterface(config.superType != TL_OBJECT ? config.superType : TL_OBJECT);

            if (canMakeEnum) {
                String shortenName = extractEnumName(qualifiedName);

                var ofMethodCode = renderer.createCode().incIndent(3);

                computedEnums.add(qualifiedName);
                var types = currTypeTree.get(qualifiedName);
                for (int i = 0; i < types.size(); i++) {
                    Type constructor = types.get(i);

                    if (i == 0) {
                        deserializeMethod.addCode("case ");
                        tlTypeOf.addCode("case ");
                    }
                    deserializeMethod.addCode("0x$L", constructor.id);
                    tlTypeOf.addCode("0x$L", constructor.id);
                    if (i + 1 < types.size()) {
                        tlTypeOf.addCodeFormatted(",$W ");
                        deserializeMethod.addCodeFormatted(",$W ");
                    } else {
                        tlTypeOf.addCode(" -> $T.class;", className).ln();
                        deserializeMethod.addCode(" -> $T.of(identifier);", className).ln();
                    }

                    String subtypeName = constructor.name.normalized();
                    String constName = screamilize(subtypeName.substring(shortenName.length()));

                    renderer.addConstant(constName, "0x" + constructor.id);

                    ofMethodCode.addStatement("case 0x$L -> $L", constructor.id, constName);

                    emptyObjectsIds.add(constructor.id);
                }

                renderer.addField(int.class, "identifier", Modifier.PRIVATE, Modifier.FINAL).complete();

                renderer.addConstructor()
                        .addParameter(int.class, "identifier")
                        .addStatement("this.identifier = identifier")
                        .complete();

                renderer.addMethod(int.class, "identifier")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("return identifier")
                        .complete();

                renderer.addMethod(className, "of", Modifier.PUBLIC, Modifier.STATIC)
                        .addParameter(int.class, "identifier")
                        .beginControlFlow("return switch (identifier) {")
                        .addCode(ofMethodCode.complete())
                        .addStatement("default -> throw new IllegalArgumentException($S + Integer.toHexString(identifier))",
                                "Incorrect type identifier: 0x")
                        .endControlFlow("};")
                        .complete();
            } else {
                for (Type subtype : currTypeTree.get(qualifiedName)) {
                    String subtypeName = name.equals(subtype.name.normalized())
                            ? "Base" + name
                            : subtype.name.normalized();
                    renderer.addPermits(ClassRef.of(packageName, subtypeName));
                }

                for (Parameter param : params) {
                    TypeRef paramType = mapType(param.type);

                    var commonAttr = renderer.addMethod(paramType, param.formattedName());

                    if (param.type.isFlag() && !param.type.isBitFlag()) {
                        commonAttr.addAnnotation(Nullable.class);
                    }

                    commonAttr.complete();
                }
            }

            fileService.writeTo(renderer);
        }
    }

    private void generateInfo() {

        for (var c : apiScheme.constructors()) {
            if (primitiveTypes.contains(c.type())) {
                String name = screamilize(c.name()) + "_ID";

                tlInfo.addField(int.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("0x" + c.id())
                        .complete();
            }
        }
    }

    private void generateAttribute(Parameter param, TopLevelRenderer renderer) {
        TypeRef paramType = mapType(param.type);

        var attribute = renderer.addMethod(paramType, param.formattedName());

        if (!param.type.isBitFlag()) {
            var ann = renderer.createAnnotation(JsonProperty.class);
            if (param.name.contains("_")) {
                ann.addAttribute(param.name);
            }

            attribute.addAnnotation(ann);
        }

        if (param.type.isBitFlag()) {
            attribute.addModifiers(Modifier.DEFAULT);
            attribute.addStatement("return ($L() & $L) != 0", param.type.flagsName,
                    bitMask.apply(param.formattedName(), Naming.As.SCREMALIZED));
        } else if (param.type.isFlag()) {
            attribute.addAnnotation(Nullable.class);
        }

        attribute.complete();
    }

    private void generateBitPosAndMask(Parameter param, TopLevelRenderer renderer) {
        int flagPos = param.type.flagPos();

        String posFieldName = bitPos.apply(param.formattedName(), Naming.As.SCREMALIZED);
        renderer.addField(byte.class, posFieldName)
                .initializer(Integer.toString(flagPos))
                .complete();

        renderer.addField(int.class, bitMask.apply(param.formattedName(), Naming.As.SCREMALIZED))
                .initializer("1 << " + posFieldName)
                .complete();
    }

    private int sizeOfPrimitive(TlProcessing.TypeNameBase type) {
        return switch (type.rawType) {
            case "int256" -> 32;
            case "int128" -> 16;
            case "long", "double" -> 8;
            case "int", "Bool", "#" -> 4;
            default -> -1;
        };
    }

    private void preparePackages() {
        try {
            String template = processingEnv.getFiler().getResource(StandardLocation.ANNOTATION_PROCESSOR_PATH,
                            "", TEMPLATE_PACKAGE_INFO).getCharContent(true)
                    .toString();

            var packages = typeTree.values().stream()
                    .flatMap(e -> e.keySet().stream())
                    .map(SourceNames::parentPackageName)
                    .filter(s -> !s.equals(BASE_PACKAGE))
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
        return switch (type.rawType) {
            case "!X" -> genericTypeRef;
            case "X" -> genericResultTypeRef;
            case "#", "int" -> PrimitiveTypeRef.INT;
            case "true", "Bool" -> PrimitiveTypeRef.BOOLEAN;
            case "long" -> PrimitiveTypeRef.LONG;
            case "double" -> PrimitiveTypeRef.DOUBLE;
            case "bytes", "int128", "int256" -> BYTE_BUF;
            case "string" -> STRING;
            case "Object" -> ClassRef.OBJECT;
            case "JSONValue" -> ClassRef.of(JsonNode.class);
            default -> {
                if (type instanceof TlProcessing.TypeName t) {

                    if (t.isFlag()) {
                        TypeNameBase innerType = t.innerType();
                        TypeRef mapped = mapType(innerType);
                        yield t.isBitFlag() ? mapped : mapped.safeBox();
                    } else if (t.isVector()) {
                        TypeNameBase innerType = t.innerType();
                        TypeRef mapped = mapType(innerType);
                        yield ParameterizedTypeRef.of(LIST, mapped.safeBox());
                    }
                }
                yield ClassRef.of(type.packageName, type.normalized());
            }
        };
    }

    private String extractEnumName(String type) {
        return currTypeTree.getOrDefault(type, List.of()).stream()
                .map(c -> c.name.normalized())
                .reduce(Strings::findCommonPart)
                .orElseGet(() -> normalizeName(type));
    }

    private String deserializeMethod(String typeName, Parameter param) {
        if (param.type.isBitSet()) {
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
        return switch (type.rawType) {
            case "Bool" -> "deserializeBoolean(payload)";
            case "int" -> "payload.readIntLE()";
            case "long" -> "payload.readLongLE()";
            case "double" -> "payload.readDoubleLE()";
            case "bytes" -> "deserializeBytes(payload)";
            case "string" -> "deserializeString(payload)";
            case "int128" -> "readInt128(payload)";
            case "int256" -> "readInt256(payload)";
            case "JSONValue" -> "deserializeJsonNode(payload)";
            default -> {
                if (type instanceof TlProcessing.TypeName t) {
                    if (t.isFlag()) {
                        throw new IllegalStateException("Unexpected flag type: " + type);
                    } else if (t.isVector()) {
                        String innerTypeRaw = t.innerType().rawType;

                        // NOTE: bare vectors (msg_container, future_salts)
                        if (t.rawType.contains("%")) {
                            yield "deserializeVector0(payload, true, TlDeserializer::deserializeMessage)";
                        } else if (t.rawType.contains("future_salt")) {
                            yield "deserializeVector0(payload, true, TlDeserializer::deserializeFutureSalt)";
                        } else {
                            String specific = switch (innerTypeRaw) {
                                case "int", "long", "bytes", "string" ->
                                        Character.toUpperCase(innerTypeRaw.charAt(0))
                                                + innerTypeRaw.substring(1);
                                default -> "";
                            };

                            yield "deserialize" + specific + "Vector(payload)";
                        }
                    }
                }
                yield "deserialize(payload)";
            }
        };
    }

    private String byteBufMethod(Parameter param) {
        return switch (param.type.rawType) {
            case "Bool", "#", "int" -> "writeIntLE";
            case "long" -> "writeLongLE";
            case "double" -> "writeDoubleLE";
            default -> throw new IllegalStateException("Unexpected value: " + param.type.rawType);
        };
    }

    private String serializeMethod(TlProcessing.TypeNameBase type) {
        return switch (type.rawType) {
            case "#", "int" -> "buf.writeIntLE($1L)";
            case "long" -> "buf.writeLongLE($1L)";
            case "double" -> "buf.writeDoubleLE($1L)";
            case "int128", "int256" -> "buf.writeBytes($1L, $1L.readerIndex(), $1L.readableBytes())";
            case "Bool" -> "buf.writeIntLE($1L ? BOOL_TRUE_ID : BOOL_FALSE_ID)";
            case "string" -> "serializeString(buf, $1L)";
            case "bytes" -> "serializeBytes(buf, $1L)";
            case "JSONValue" -> "serializeJsonNode(buf, $1L)";
            case "Object" -> "serializeUnknown(buf, $1L)";
            default -> {
                if (type instanceof TlProcessing.TypeName t && t.isVector()) {
                    String innerTypeRaw = t.innerType().rawType;
                    String specific = switch (innerTypeRaw.toLowerCase()) {
                        case "int", "long", "bytes", "string" ->
                                Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                        default -> "";
                    };
                    yield "serialize" + specific + "Vector(buf, $1L)";
                }
                yield "serialize(buf, $1L)";
            }
        };
    }

    private void writeSerializeMethod(MethodRenderer<TopLevelRenderer> renderer, Parameter param) {
        if (param.type.isFlag()) {
            renderer.addStatement("var $1L = payload.$1L()", param.formattedName());
            String serializeMethod = serializeMethod(param.type.innerType());
            renderer.addStatement("if ($1L != null) " + serializeMethod, param.formattedName());
        } else if (param.type.isVector()) {
            String innerTypeRaw = param.type.innerType().rawType;
            String specific = switch (innerTypeRaw.toLowerCase()) {
                case "int", "long", "bytes", "string" ->
                        Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                default -> "";
            };

            renderer.addStatement("serialize" + specific + "Vector(buf, payload.$L())", param.formattedName());
        } else {
            switch (param.type.rawType) {
                case "int", "#" -> renderer.addStatement("buf.writeIntLE(payload.$L())", param.formattedName());
                case "long" -> renderer.addStatement("buf.writeLongLE(payload.$L())", param.formattedName());
                case "double" -> renderer.addStatement("buf.writeDoubleLE(payload.$L())", param.formattedName());
                case "int128", "int256" -> {
                    // writeBytes(ByteBuf) changes reader and writer indexes which may break semantic
                    // But our implementation always return .duplicate() of byte buffer
                    renderer.addStatement("var $1L = payload.$1L()", param.formattedName());
                    renderer.addStatement("buf.writeBytes($1L, $1L.readerIndex(), $1L.readableBytes())", param.formattedName());
                }
                case "Bool" -> renderer.addStatement("buf.writeIntLE(payload.$L() ? BOOL_TRUE_ID : BOOL_FALSE_ID)", param.formattedName());
                case "string" -> renderer.addStatement("serializeString(buf, payload.$L())", param.formattedName());
                case "bytes" -> renderer.addStatement("serializeBytes(buf, payload.$L())", param.formattedName());
                case "JSONValue" -> renderer.addStatement("serializeJsonNode(buf, payload.$L())", param.formattedName());
                case "Object" -> renderer.addStatement("serializeUnknown(buf, payload.$L())", param.formattedName());
                default -> renderer.addStatement("serialize(buf, payload.$L())", param.formattedName());
            }
        }
    }

    private String sizeOfMethod(TypeNameBase type) {
        return switch (type.rawType) {
            case "int", "#", "long", "int128", "int256", "Bool", "double" -> throw new IllegalStateException();
            case "string", "bytes" -> "sizeOf0($L)";
            case "JSONValue" -> "sizeOfJsonNode($L)";
            case "Object" -> throw new IllegalStateException();
            default -> {
                if (type instanceof TlProcessing.TypeName t && t.isVector()) {
                    String innerTypeRaw = t.innerType().rawType;
                    String specific = switch (innerTypeRaw) {
                        case "int", "long", "bytes", "string" ->
                                Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                        default -> "";
                    };
                    yield "sizeOf" + specific + "Vector($L)";
                }
                yield "sizeOf($L)";
            }
        };
    }

    private String sizeOfMethod(Parameter param) {
        return switch (param.type.rawType) {
            case "int", "#", "long", "int128", "int256", "Bool", "double" -> throw new IllegalStateException();
            case "string", "bytes" -> "sizeOf0(payload.$L())";
            case "JSONValue" -> "sizeOfJsonNode(payload.$L())";
            case "Object" -> "sizeOfUnknown(payload.$L())";
            default -> {
                if (param.type.isVector()) {
                    String innerTypeRaw = param.type.innerType().rawType;
                    String specific = switch (innerTypeRaw) {
                        case "int", "long", "bytes", "string" ->
                                Character.toUpperCase(innerTypeRaw.charAt(0)) + innerTypeRaw.substring(1);
                        default -> "";
                    };
                    yield "sizeOf" + specific + "Vector(payload.$L())";
                } else if (param.type.isBitFlag() || param.type.isFlag()) {
                    throw new IllegalStateException();
                } else {
                    yield "sizeOf(payload.$L())";
                }
            }
        };
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
        return Supertypes.predicates.stream()
                .filter(t -> t.getT1().test(name))
                .map(Tuple2::getT2)
                .collect(Collectors.toList());
    }
}
