package telegram4j.tl.generator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.immutables.value.Value;
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
import telegram4j.tl.parser.TlTrees;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
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
import static telegram4j.tl.generator.SourceNames.normalizeName;
import static telegram4j.tl.generator.SourceNames.parentPackageName;
import static telegram4j.tl.generator.Strings.camelize;
import static telegram4j.tl.generator.Strings.screamilize;

@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedAnnotationTypes("telegram4j.tl.generator.GenerateSchema")
public class SchemaGenerator extends AbstractProcessor {

    // TODO: add serialization for JsonVALUE in TlSerializer.serialize(...)

    private final Map<String, Type> computed = new HashMap<>();

    private PackageElement currentElement;
    private List<Tuple2<TlTrees.Scheme, Configuration>> schemas;
    private Map<TlTrees.Scheme, Map<String, List<Type>>> typeTree;
    private List<Tuple2<Predicate<String>, ClassName>> superTypes;

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
                    ClassName type = ClassName.bestGuess(BASE_PACKAGE + '.' + e.getKey());

                    Set<String> set = null;
                    Predicate<String> prev = null;
                    for (String s : e.getValue()) {
                        if (s.startsWith("$")) {
                            var patternFilter = Pattern.compile(s.substring(1)).asMatchPredicate();
                            if (prev == null)
                                prev = patternFilter;
                            prev = prev.or(patternFilter);
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
                generatePrimitives();

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
                finalizeSerialization();
                iteration++; // end
                break;
        }

        return true;
    }

    private void finalizeSerialization() {

        for (int i = 0; i < emptyObjectsIds.size(); i++) {
            String id = emptyObjectsIds.get(i);
            if (i + 1 == emptyObjectsIds.size()) {
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
        for (var rawMethod : schema.methods()) {
            if (ignoredTypes.contains(rawMethod.type())) {
                continue;
            }

            Type method = Type.parse(config, rawMethod);

            boolean singleton = true;
            boolean generic = false;
            boolean hasObjectFields = false;
            for (Parameter p : method.parameters) {
                if (!generic && p.type.rawType.equals("!X")) generic = true;
                if (!hasObjectFields && sizeOfMethod(p) != null) hasObjectFields = true;
                if (!p.type.rawType.equals("#") && !p.type.isFlag()) singleton = false;
            }

            TypeSpec.Builder spec = TypeSpec.interfaceBuilder(method.name.normalized())
                    .addModifiers(Modifier.PUBLIC);

            if (generic) {
                spec.addTypeVariable(genericResultTypeRef);
                spec.addTypeVariable(genericTypeRef.withBounds(wildcardMethodType));
            }

            TypeName returnType = ParameterizedTypeName.get(
                    ClassName.get(TlMethod.class),
                    mapType(method.type).box());

            String name = method.name.normalized();
            spec.addSuperinterfaces(addSuperTypes(name));

            spec.addSuperinterface(returnType);
            if (config.superType != null) {
                spec.addSuperinterface(config.superType);
            }

            spec.addField(FieldSpec.builder(TypeName.INT, "ID",
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + method.id)
                    .build());

            ClassName immutableTypeRaw = ClassName.get(method.name.packageName, "Immutable" + name);
            ClassName immutableTypeBuilderRaw = ClassName.get(method.name.packageName, "Immutable" + name, "Builder");
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

            if (method.parameters.isEmpty()) {
                emptyObjectsIds.add(method.id);
            } else {
                ClassName typeRaw = ClassName.get(method.name.packageName, name);
                TypeName payloadType = generic
                        ? ParameterizedTypeName.get(typeRaw, WildcardTypeName.subtypeOf(TypeName.OBJECT),
                        wildcardUnboundedMethodType)
                        : typeRaw;

                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(method.name.rawType)), computedSerializers);

                serializeMethod.addCode("case 0x$L: return $L(buf, ($T) payload);\n",
                        method.id, serializeMethodName, payloadType);

                MethodSpec.Builder serializerBuilder = MethodSpec.methodBuilder(serializeMethodName)
                        .returns(ByteBuf.class)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameters(Arrays.asList(
                                ParameterSpec.builder(ByteBuf.class, "buf").build(),
                                ParameterSpec.builder(payloadType, "payload").build()));

                CodeBlock.Builder sizeOfBlock = CodeBlock.builder();
                CodeBlock.Builder serPrecomputeBlock = CodeBlock.builder(); // variables
                CodeBlock.Builder std = CodeBlock.builder();

                boolean fluentStyle = !hasObjectFields;

                int size = 4;
                StringJoiner sizes = new StringJoiner(" + ");
                for (Parameter param : method.parameters) {
                    String sizeMethod = sizeOfMethod(param);

                    int s = sizeOf(param.type);
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else if (sizeMethod != null) {
                        String sizeVar = param.formattedName() + "Size";

                        sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());
                        sizes.add(sizeVar);
                    }

                    String ser = serializeMethod(param);
                    if (ser != null) {
                        if (sizeMethod != null) {
                            std.addStatement(ser, param.formattedName());
                        } else {
                            String met = byteBufMethod(param);
                            if (fluentStyle) {
                                std.add("\n\t\t." + met + "(" + ser + ")", param.formattedName());
                            } else {
                                std.addStatement("buf." + met + "(" + ser + ")", param.formattedName());
                            }
                        }
                    }

                    spec.addMethod(createAttribute(method, param));
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
                            camelize(parentPackageName(method.name.rawType)), computedSizeOfs);

                    sizeOfMethod.addCode("case 0x$L: return $L(($T) payload);\n",
                            method.id, sizeOfMethodName, payloadType);

                    serializer.addMethod(MethodSpec.methodBuilder(sizeOfMethodName)
                            .returns(int.class)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .addParameter(ParameterSpec.builder(payloadType, "payload").build())
                            .addCode(sizeOfBlock.build())
                            .build());
                } else {
                    sizeOfMethod.addCode("case 0x$L: return $L;\n", method.id, size);
                }
            }

            writeTo(JavaFile.builder(method.name.packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());
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

            boolean multiple = currTypeTree.getOrDefault(packageName + "." + type, List.of()).size() > 1;

            // add Base* prefix to prevent matching with type name, e.g. SecureValueError
            if (type.equals(name) && multiple) {
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
                    .addSuperinterfaces(addSuperTypes(name));

            if (multiple) {
                spec.addSuperinterface(ClassName.get(packageName, type));
            } else if (config.superType != null) {
                spec.addSuperinterface(config.superType);
            } else {
                spec.addSuperinterface(TlObject.class);
            }

            spec.addField(FieldSpec.builder(TypeName.INT, "ID",
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("0x" + constructor.id)
                    .build());

            spec.addMethod(MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.get(packageName, "Immutable" + name, "Builder"))
                    .addCode("return Immutable$L.builder();", name)
                    .build());

            var collect = new LinkedHashSet<>(constructor.parameters);
            collectAttributesRecursive(type, collect);

            var attributes = new ArrayList<>(collect);
            // todo: replace to bit-flags?
            boolean singleton = true;
            int flagsCount = 0;
            boolean firstIsFlags = false;
            boolean hasObjectFields = false;
            for (int i = 0; i < attributes.size(); i++) {
                Parameter p = attributes.get(i);
                if (p.type.rawType.equals("#")) {
                    firstIsFlags = i == 0;
                    flagsCount++;
                }
                if (!hasObjectFields && sizeOfMethod(p) != null) hasObjectFields = true;
                if (!p.type.rawType.equals("#") && !p.type.isFlag()) singleton = false;
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
                deserializeMethod.addCode("case 0x$L: return (T) $T.of();\n", constructor.id, typeName);

                emptyObjectsIds.add(constructor.id);
            } else {
                String serializeMethodName = uniqueMethodName("serialize", name, () ->
                        camelize(parentPackageName(constructor.name.rawType)), computedSerializers);

                String deserializeMethodName = uniqueMethodName("deserialize", name, () ->
                        camelize(parentPackageName(constructor.name.rawType)), computedDeserializers);

                TypeName payloadType = ClassName.get(packageName, name);

                serializeMethod.addCode("case 0x$L: return $L(buf, ($T) payload);\n",
                        constructor.id, serializeMethodName, payloadType);

                deserializeMethod.addCode("case 0x$L: return (T) $L(payload);\n",
                        constructor.id, deserializeMethodName);

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
                    String sizeMethod = sizeOfMethod(param);

                    int s = sizeOf(param.type);
                    if (s != -1) {
                        size = Math.addExact(size, s);
                    } else if (sizeMethod != null) {
                        String sizeVar = param.formattedName() + "Size";

                        sizeOfBlock.addStatement("int $L = " + sizeMethod, sizeVar, param.formattedName());
                        sizes.add(sizeVar);
                    }

                    String ser = serializeMethod(param);
                    if (ser != null) {
                        if (sizeMethod != null) {
                            std.addStatement(ser, param.formattedName());
                        } else {
                            String met = byteBufMethod(param);
                            if (fluentStyle) {
                                std.add("\n\t\t." + met + "(" + ser + ")", param.formattedName());
                            } else {
                                std.addStatement("buf." + met + "(" + ser + ")", param.formattedName());
                            }
                        }
                    }

                    if (param.type.rawType.equals("#") && !firstIsFlags) {
                        deserializerBuilder.addCode(";\n");
                        deserializerBuilder.addStatement("int $L = payload.readIntLE()", param.formattedName());
                        if (--flagsRemaining == 0) {
                            deserializerBuilder.addCode("return builder.$1L($1L)", param.formattedName());
                        } else {
                            deserializerBuilder.addCode("builder.$1L($1L)", param.formattedName());
                        }
                    } else {
                        deserializerBuilder.addCode("\n\t\t.$L(" + deserializeMethod(param) + ")", param.formattedName());
                    }

                    spec.addMethod(createAttribute(constructor, param));
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
                            camelize(parentPackageName(constructor.name.rawType)), computedSizeOfs);

                    sizeOfMethod.addCode("case 0x$L: return $L(($T) payload);\n",
                            constructor.id, sizeOfMethodName, payloadType);

                    serializer.addMethod(MethodSpec.methodBuilder(sizeOfMethodName)
                            .returns(int.class)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .addParameter(ParameterSpec.builder(payloadType, "payload").build())
                            .addCode(sizeOfBlock.build())
                            .build());
                } else {
                    sizeOfMethod.addCode("case 0x$L: return $L;\n", constructor.id, size);
                }
            }

            writeTo(JavaFile.builder(packageName, spec.build())
                    .indent(INDENT)
                    .skipJavaLangImports(true)
                    .build());

            computed.put(packageName + "." + name, constructor);
        }
    }

    private MethodSpec createAttribute(Type type, Parameter param) {
        MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                .addModifiers(Modifier.PUBLIC);

        TypeName paramType = mapType(param.type);

        if (param.type.rawType.equals("#")) {
            attribute.addModifiers(Modifier.DEFAULT);
            String precompute = type.parameters.stream()
                    .filter(p -> p.type.isFlag() && p.type.flagsName().equals(param.formattedName()))
                    .map(flag -> {
                        int flagPos = flag.type.flagPos();
                        String innerTypeRaw = flag.type.innerType().rawType;
                        return String.format("(%s()%s ? 1 : 0) << 0x%x", flag.formattedName(),
                                innerTypeRaw.equals("true") ? "" : " != null", flagPos);
                    })
                    .collect(Collectors.joining(" | "));

            attribute.addCode("return " + precompute + ";");
        } else if (param.type.rawType.endsWith("true")) {
            attribute.addModifiers(Modifier.DEFAULT);
            attribute.addCode("return false;");
        } else if (param.type.isFlag()) {
            paramType = wrapOptional(attribute, paramType, param);
            attribute.addModifiers(Modifier.ABSTRACT);
        } else {
            attribute.addModifiers(Modifier.ABSTRACT);
        }

        return attribute.returns(paramType).build();
    }

    private int sizeOf(TypeNameBase type) {
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
                if (type.rawType.endsWith("true")) {
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

            TypeSpec.Builder spec = canMakeEnum
                    ? TypeSpec.enumBuilder(name)
                    : TypeSpec.interfaceBuilder(name);

            spec.addModifiers(Modifier.PUBLIC);
            if (config.superType != null) {
                spec.addSuperinterface(config.superType);
            } else {
                spec.addSuperinterface(TlObject.class);
            }

            if (canMakeEnum) {
                String shortenName = extractEnumName(qualifiedName);
                ClassName className = ClassName.get(packageName, name);

                MethodSpec.Builder ofMethod = MethodSpec.methodBuilder("of")
                        .addParameter(int.class, "identifier")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(className)
                        .beginControlFlow("switch (identifier)");

                var types = currTypeTree.get(qualifiedName);
                for (int i = 0; i < types.size(); i++) {
                    Type constructor = types.get(i);

                    if (i + 1 == types.size()) {
                        deserializeMethod.addCode("case 0x$L: return (T) $T.of(identifier);\n", constructor.id, className);
                    } else {
                        deserializeMethod.addCode("case 0x$L:\n", constructor.id);
                    }

                    String subtypeName = constructor.name.normalized();
                    String constName = screamilize(subtypeName.substring(shortenName.length()));

                    spec.addEnumConstant(constName, TypeSpec.anonymousClassBuilder(
                                    "0x$L", constructor.id)
                            .build());

                    ofMethod.addCode("case 0x$L: return $L;\n", constructor.id, constName);

                    emptyObjectsIds.add(constructor.id);

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
                    TypeName paramType = mapType(param.type);

                    MethodSpec.Builder attribute = MethodSpec.methodBuilder(param.formattedName())
                            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

                    if (!param.type.rawType.endsWith("true") && (param.type.isFlag() ||
                            isOptionalInSuccessors(qualifiedName, param))) {
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

    private boolean isOptionalInSuccessors(String qualifiedName, Parameter param) {
        return currTypeTree.getOrDefault(qualifiedName, List.of()).stream()
                .flatMap(c -> c.parameters.stream())
                .anyMatch(p -> p.type.isFlag() &&
                        p.type.innerType().rawType.equals(param.type.rawType) &&
                        p.name.equals(param.name));
    }

    private void generatePrimitives() {

        TypeSpec.Builder spec = TypeSpec.classBuilder("TlPrimitives")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(privateConstructor);

        for (var c : apiScheme.constructors()) {
            if (primitiveTypes.contains(c.type())) {
                String name = screamilize(c.name()) + "_ID";

                spec.addField(FieldSpec.builder(int.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("0x" + c.id())
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

    private TypeName mapType(TlProcessing.TypeNameBase type) {
        switch (type.rawType) {
            case "!X": return genericTypeRef;
            case "X": return genericResultTypeRef;
            case "#":
            case "int": return TypeName.INT;
            case "true":
            case "Bool": return TypeName.BOOLEAN;
            case "long": return TypeName.LONG;
            case "double": return TypeName.DOUBLE;
            case "bytes":
            case "int128":
            case "int256": return ClassName.get(ByteBuf.class);
            case "string": return ClassName.get(String.class);
            case "Object": return ClassName.OBJECT;
            case "JSONValue": return ClassName.get(JsonNode.class);
            default:
                if (type instanceof TlProcessing.TypeName) {
                    TlProcessing.TypeName t = (TlProcessing.TypeName) type;

                    if (t.isFlag()) {
                        TypeNameBase innerType = t.innerType();
                        TypeName mapped = mapType(innerType);
                        return innerType.rawType.equals("true") ? mapped : mapped.box();
                    } else if (t.isVector()) {
                        TypeNameBase innerType = t.innerType();
                        TypeName mapped = mapType(innerType);
                        return ParameterizedTypeName.get(ClassName.get(List.class), mapped.box());
                    }
                }

                return ClassName.get(type.packageName, type.normalized());
        }
    }

    private void collectAttributesRecursive(String name, Set<Parameter> params) {
        Type constructor = computed.get(name);
        if (constructor == null || constructor.name.rawType.equals(name)) {
            return;
        }
        params.addAll(constructor.parameters);
        collectAttributesRecursive(constructor.name.rawType, params);
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
        return currTypeTree.getOrDefault(type, List.of()).stream()
                .map(c -> c.name.normalized())
                .reduce(Strings::findCommonPart)
                .orElseGet(() -> normalizeName(type));
    }

    private String deserializeMethod(Parameter param) {
        if (param.type.rawType.equals("#")) {
            return param.formattedName();
        }
        return deserializeMethod0(param.type);
    }

    private String deserializeMethod0(TypeNameBase type) {
        switch (type.rawType) {
            case "#": throw new IllegalStateException();
            case "Bool": return "payload.readIntLE() == BOOL_TRUE_ID";
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
                        int flagPos = t.flagPos();
                        String flagsName = t.flagsName();
                        TypeNameBase innerType = t.innerType();

                        String pos = Integer.toHexString(1 << flagPos);
                        if (innerType.rawType.equals("true")) {
                            return "(" + flagsName + " & 0x" + pos + ") != 0";
                        }

                        String innerMethod = deserializeMethod0(innerType);
                        return "(" + flagsName + " & 0x" + pos + ") != 0 ? " + innerMethod + " : null";
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
            case "int128":
            case "int256": return "writeBytes";
            default: throw new IllegalStateException("Unexpected value: " + param.type.rawType);
        }
    }

    @Nullable
    private String serializeMethod(Parameter param) {
        switch (param.type.rawType) {
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
                } else if (param.type.rawType.endsWith("true")) {
                    return null;
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
                } else if (param.type.rawType.endsWith("true")) {
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

    private TypeName wrapOptional(MethodSpec.Builder attribute, TypeName type, Parameter param) {
        if (param.type.rawType.contains("bytes")) {
            return ParameterizedTypeName.get(ClassName.get(Optional.class), type);
        }

        attribute.addAnnotation(Nullable.class);
        return type.box();
    }

    private Map<String, List<Type>> collectTypeTree(Configuration config, TlTrees.Scheme schema) {
        return schema.constructors().stream()
                .filter(c -> !ignoredTypes.contains(c.type()) && !primitiveTypes.contains(c.type()))
                .map(e -> Type.parse(config, e))
                .collect(Collectors.groupingBy(c -> c.type.packageName + "." + c.type.normalized()));
    }

    private List<ClassName> addSuperTypes(String name) { // normalized constr/method name
        return superTypes.stream()
                .filter(t -> t.getT1().test(name))
                .map(Tuple2::getT2)
                .collect(Collectors.toList());
    }
}
