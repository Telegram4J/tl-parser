package telegram4j.tl.generator;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.netty.buffer.ByteBufUtil;
import reactor.util.annotation.Nullable;
import telegram4j.tl.api.TlEncodingUtil;
import telegram4j.tl.generator.renderer.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static telegram4j.tl.generator.SchemaGeneratorConsts.*;
import static telegram4j.tl.generator.SchemaGeneratorConsts.Style.newValue;
import static telegram4j.tl.generator.SchemaGeneratorConsts.Style.with;

class ImmutableGenerator {

    private static final ClassRef UTILITY = ClassRef.of(TlEncodingUtil.class);

    private final FileService fileService;
    private final Elements elements;

    ImmutableGenerator(FileService fileService, Elements elements) {
        this.fileService = fileService;
        this.elements = elements;
    }

    public void process(ValueType type) {
        var renderer = ClassRenderer.create(type.immutableType.rawType, ClassRenderer.Kind.CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addTypeVariables(type.typeVars)
                .addInterface(type.baseType);

        CompletionDeferrer pending = new CompletionDeferrer();
        boolean singleton = type.flags.contains(ValueType.Flag.SINGLETON);

        // region generate fields
        if (singleton) { // TODO: unhandled generics
            renderer.addField(type.immutableType, "INSTANCE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T()", type.immutableType)
                    .complete();
        }

        for (ValueAttribute a : type.generated) {
            renderer.addField(unboxOptional(a, type), a.name, Modifier.PRIVATE, Modifier.FINAL).complete();
        }
        // endregion

        // region constructors

        if (singleton) {
            var singletonConstructor = renderer.addConstructor(Modifier.PRIVATE);

            for (ValueAttribute a : type.generated) {
                singletonConstructor.addStatement("$L = $L", a.name, defaultValueFor(unboxOptional(a, type)));
            }

            singletonConstructor.complete();
        }

        // constructor with mandatory parameters
        if (!type.flags.contains(ValueType.Flag.CAN_OMIT_OF_METHOD)) {
            var mandatoryConstructorBody = renderer.createCode().incIndent(2);
            var mandatoryConstructor = renderer.addConstructor(Modifier.PRIVATE);
            var mandatoryOf = renderer.addMethod(type.immutableType, "of",
                            Modifier.PUBLIC, Modifier.STATIC)
                    .addTypeVariables(type.typeVars);

            StringJoiner params = new StringJoiner(",$W ");

            // I want to initialize the fields in order:
            // [ mandatory fields ]
            // \n
            // [ optional fields ]

            var sorted = type.generated.stream()
                    .sorted(Comparator.comparingInt(d -> {
                        if (d.flags.contains(ValueAttribute.Flag.BIT_SET)) {
                            Counter c = type.flagsCount.get(d.name);
                            if (c == null || c.value == 0) {
                                return 2;
                            }
                        }
                        return d.flags.contains(ValueAttribute.Flag.OPTIONAL) ? 1 : 0;
                    }))
                    .collect(Collectors.toList());

            boolean prevNotOpt = true;
            for (ValueAttribute a : sorted) {
                if (prevNotOpt && a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                    mandatoryConstructorBody.ln();
                }

                if (!prevNotOpt && a.flags.contains(ValueAttribute.Flag.BIT_SET)) {
                    mandatoryConstructorBody.addStatement("$L = 0", a.name);
                } else if (!a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                    TypeRef listElement = unwrap(a.type, LIST);
                    TypeRef paramType = a.type;
                    if (listElement != a.type) // Iterable<? extends ListElement>
                        paramType = ParameterizedTypeRef.of(ITERABLE, expandBounds(listElement));

                    mandatoryConstructor.addParameter(paramType, a.name);

                    if (a.type instanceof PrimitiveTypeRef) {
                        mandatoryConstructorBody.addStatement("this.$1L = $1L", a.name);
                    } else if (listElement != a.type) {
                        if (listElement == BYTE_BUF) {
                            mandatoryConstructorBody.addStatement("this.$1L = $2T.stream($1L.spliterator(), false)$B" +
                                            ".map($3T::copyAsUnpooled)$B.collect($4T.toUnmodifiableList())",
                                    a.name, StreamSupport.class, UTILITY, Collectors.class);
                        } else {
                            mandatoryConstructorBody.addStatement("this.$1L = $2T.copyList($1L)", a.name, UTILITY);
                        }
                    } else if (a.type == BYTE_BUF) {
                        mandatoryConstructorBody.addStatement("this.$1L = $2T.copyAsUnpooled($1L)", a.name, UTILITY);
                    } else {
                        mandatoryConstructorBody.addStatement("this.$1L = $2T.requireNonNull($1L)", a.name, Objects.class);
                    }

                    params.add(a.name);
                    mandatoryOf.addParameter(paramType, a.name);
                } else {
                    TypeRef unwrapped = unboxOptional(a, type);
                    mandatoryConstructorBody.addStatement("$L = $L", a.name, defaultValueFor(unwrapped));
                }

                prevNotOpt = !a.flags.contains(ValueAttribute.Flag.OPTIONAL);
            }

            for (ValueAttribute a : sorted) {
                if (a.type == BYTE_BUF && a.maxSize != -1) {
                    mandatoryOf.beginControlFlow("if ($L.readableBytes() != $L) {", a.name, a.maxSize);
                    mandatoryOf.addStatement("throw new IllegalArgumentException($S + $L.readableBytes() + $S)",
                            "size of value ", a.name, " != " + a.maxSize);
                    mandatoryOf.endControlFlow();
                }
            }

            for (ValueAttribute a : sorted) {
                Counter c;
                if (a.flags.contains(ValueAttribute.Flag.BIT_SET) &&
                    (c = type.flagsCount.get(a.name)) != null && c.value != 0) {
                    generateValueBitsMask(type, a, a.name, mandatoryOf);
                }
            }

            if (singleton) {
                mandatoryOf.addStatement("return canonize(new $T(" + params + "))", type.immutableType);
            } else {
                mandatoryOf.addStatement("return new $T(" + params + ")", type.immutableType);
            }

            mandatoryConstructor.addCode(mandatoryConstructorBody.complete());
            mandatoryConstructor.complete();

            pending.add(mandatoryOf);
        }

        var builderConstructor = renderer.addConstructor(Modifier.PRIVATE)
                .addParameter(type.builderType, "builder");

        for (ValueAttribute a : type.generated) {
            StringBuilder format = new StringBuilder("this.$1L = ");
            TypeRef listElement = unwrap(a.type, LIST);
            BitSet bs;
            boolean isOptional = a.flags.contains(ValueAttribute.Flag.OPTIONAL) &&
                    (listElement != a.type || a.type.safeUnbox() instanceof PrimitiveTypeRef &&
                    ((bs = type.usedBits.get(a.flagsName)) == null || !bs.get(a.flagPos)));
            if (isOptional) {
                format.append("builder.$1L != null ? ");
            }

            if (listElement != a.type) {
                format.append("$4T.copyOf(builder.$1L)");
            } else {
                format.append("builder.$1L");
            }

            if (isOptional) {
                format.append(" : ").append(defaultValueFor(a.type.safeUnbox()));
            }

            builderConstructor.addStatement(format, a.name, a.flagsName, a.flagMask, LIST);
        }

        builderConstructor.complete();

        // constructor for with* methods

        // - no reference fields
        // - no optional fields
        if (!type.flags.contains(ValueType.Flag.CAN_OMIT_COPY_CONSTRUCTOR)) {
            var allConstructorBody = renderer.createCode().incIndent(2);
            var allConstructor = renderer.addConstructor(Modifier.PRIVATE);

            // have a reference fields, need to stub.
            // This is necessary because this constructor does not contain null checks
            if (type.flags.contains(ValueType.Flag.NEED_STUB_PARAM)) {
                allConstructor.addParameter(Void.class, "synthetic0");
            }

            for (ValueAttribute a : type.generated) {
                allConstructor.addParameter(unboxOptional(a, type), a.name);
                allConstructorBody.addStatement("this.$1L = $1L", a.name);
            }

            allConstructor.addCode(allConstructorBody.complete());
            allConstructor.complete();
        }

        // endregion

        // region static methods

        if (singleton) { // unhandled possible generics
            renderer.addMethod(type.immutableType, "canonize", Modifier.PRIVATE, Modifier.STATIC)
                    .addParameter(type.immutableType, "instance")
                    .addStatement("return INSTANCE.equalsTo(instance) ? INSTANCE : instance")
                    .complete();

            renderer.addMethod(type.immutableType, "of", Modifier.PUBLIC, Modifier.STATIC)
                    .addStatement("return INSTANCE")
                    .complete();
        }

        // To complete mandatory of(...)
        pending.complete();

        var copyOf = renderer.addMethod(type.immutableType, "copyOf", Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariables(type.typeVars)
                .addParameter(type.baseType, "instance")
                .beginControlFlow("if (instance instanceof $T) {", type.immutableType.withTypeArguments(
                        Collections.nCopies(type.typeVars.size(), WildcardTypeRef.none())))
                .addStatement("return ($T) instance", type.immutableType)
                .endControlFlow();

        if (!type.typeVars.isEmpty()) {
            copyOf.addStatement("return $L.<$L>builder().from(instance).build()",
                    type.immutableType.rawType, type.typeVarNames.stream()
                            .map(r -> r.name)
                            .collect(Collectors.joining(", ")));
        } else {
            copyOf.addStatement("return builder().from(instance).build()");
        }

        copyOf.complete();

        renderer.addMethod(type.builderType, "builder", Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariables(type.typeVars)
                .addStatement("return new Builder()")
                .complete();

        // endregion

        // region interface methods

        for (ValueAttribute a : type.generated) {
            var attr = renderer.addMethod(a.type, a.name);
            if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                attr.addAnnotation(Nullable.class);
            }

            attr.addAnnotation(Override.class);
            attr.addModifiers(Modifier.PUBLIC);

            TypeRef listElement = unwrap(a.type, LIST);

            boolean opt = a.flags.contains(ValueAttribute.Flag.OPTIONAL);
            BitSet bs;
            boolean primitiveOpt = opt && a.type.safeUnbox() instanceof PrimitiveTypeRef
                    && ((bs = type.usedBits.get(a.flagsName)) == null || !bs.get(a.flagPos));
            StringBuilder format = new StringBuilder("return ");
            if (opt && listElement == BYTE_BUF) {
                format.append("$L != null ? ");
            } else if (primitiveOpt) {
                format.append("($2L & $3L) != 0 ? ");
            }

            if (listElement != a.type && listElement == BYTE_BUF) {
                format.append("$1L.stream().map(ByteBuf::duplicate).collect(Collectors.toList())");
            } else if (a.type == BYTE_BUF) {
                format.append("$1L.duplicate()");
            } else {
                format.append("$1L");
            }

            if (opt && (listElement == BYTE_BUF || primitiveOpt)) {
                format.append(" : null");
            }

            attr.addStatement(format, a.name, a.flagsName, a.flagMask);
            attr.complete();
        }

        // endregion

        // region with* methods

        for (ValueAttribute a : type.attributes) {
            generateWither(type, a, renderer, pending);

            pending.complete();
        }

        // endregion

        // region object methods

        var unknownTypeParams = type.baseType.withTypeArguments(
                Collections.nCopies(type.typeVars.size(), WildcardTypeRef.none()));
        MethodRenderer<TopLevelRenderer> equals0;
        if (singleton) {
            equals0 = renderer.addMethod(boolean.class, "equalsTo")
                    .addModifiers(Modifier.PRIVATE)
                    .addParameter(type.baseType, type.equalsName);
        } else {
            equals0 = renderer.addMethod(boolean.class, "equals")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassRef.OBJECT, "o")
                    .addStatement("if (this == o) return true")
                    .addStatement("if (!(o instanceof $T)) return false", unknownTypeParams)
                    .addStatement("$1T $2L = ($1T) o", unknownTypeParams, type.equalsName)
                    .addCode("return ID == $L.identifier() &&", type.equalsName)
                    .incIndent(2).ln();
        }

        var hashCode = renderer.addMethod(int.class, "hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("int $L = 5381", type.hashCodeName)
                .addStatement("$1L += ($1L << 5) + ID", type.hashCodeName);

        var toString = renderer.addMethod(STRING, "toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addCode("return \"$L#$L{\" +", type.baseType.rawType.name, type.identifier)
                .incIndent(2).ln();

        for (int i = 0, n = type.generated.size(); i < n; i++) {
            ValueAttribute a = type.generated.get(i);

            TypeRef unwrapped = unboxOptional(a, type);

            // region hashCode
            if (unwrapped == PrimitiveTypeRef.DOUBLE) {
                hashCode.addStatement("$1L += ($1L << 5) + Double.hashCode($2L)", type.hashCodeName, a.name);
            } else if (unwrapped == PrimitiveTypeRef.INT) {
                hashCode.addStatement("$1L += ($1L << 5) + $2L", type.hashCodeName, a.name);
            } else if (unwrapped == PrimitiveTypeRef.BOOLEAN) {
                if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                    hashCode.addStatement("$1L += ($1L << 5) + (this." + a.flagsName() + " & " + a.flagMask()
                            + ") != 0 ? Boolean.hashCode($2L) : 0", type.hashCodeName, a.name);
                } else {
                    hashCode.addStatement("$1L += ($1L << 5) + Boolean.hashCode($2L)", type.hashCodeName, a.name);
                }
            } else if (unwrapped == PrimitiveTypeRef.LONG) {
                hashCode.addStatement("$1L += ($1L << 5) + Long.hashCode($2L)", type.hashCodeName, a.name);
            } else if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                hashCode.addStatement("$1L += ($1L << 5) + $2T.hashCode($3L)", type.hashCodeName, Objects.class, a.name);
            } else {
                hashCode.addStatement("$1L += ($1L << 5) + $2L.hashCode()", type.hashCodeName, a.name);
            }

            // endregion

            // region equals

            if (i == 0 && singleton) {
                equals0.addCode("return ");
            }

            if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                equals0.addCode("$T.equals($2L(), $3L.$2L())", Objects.class, a.name, type.equalsName);
            } else if (unwrapped == PrimitiveTypeRef.DOUBLE) {
                equals0.addCode("Double.doubleToLongBits($1L) == Double.doubleToLongBits($2L.$1L())", a.name, type.equalsName);
            } else if (unwrapped instanceof PrimitiveTypeRef) {
                equals0.addCode("$1L == $2L.$1L()", a.name, type.equalsName);
            } else {
                equals0.addCode("$1L.equals($2L.$1L())", a.name, type.equalsName);
            }

            if (i != n - 1) {
                equals0.addCode(" &&").ln();
            }

            if (i == 0 && singleton) {
                equals0.incIndent(2);
            }

            // endregion

            // region toString

            if (i != 0) {
                ValueAttribute prev = type.generated.get(i - 1);
                boolean isPrevStr = unboxOptional(prev, type) == STRING;

                if (isPrevStr) {
                    toString.addCode("\"', ");
                } else {
                    toString.addCode("\", ");
                }
            } else {
                toString.addCode('"');
            }

            toString.addCode(a.name);
            if (unwrapped == STRING) {
                toString.addCode("='\" + ");
            } else {
                toString.addCode("=\" + ");
            }

            TypeRef listElement = unwrap(a.type, LIST);

            if (a.flags.contains(ValueAttribute.Flag.OPTIONAL) && unwrapped instanceof PrimitiveTypeRef) {
                toString.addCode("(($L & $L) != 0 ? $L : \"null\")", a.flagsName(), a.flagMask(), a.name);
            } else if (a.flags.contains(ValueAttribute.Flag.BIT_SET)) {
                toString.addCode("Integer.toBinaryString($L)", a.name);
            } else if (listElement == BYTE_BUF) {
                StringBuilder format = new StringBuilder();
                if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                    format.append("($1L != null ? ");
                }

                if (listElement != a.type) {
                    format.append("$1L.stream()$B.map($2T::hexDump)$B.collect($3T.joining(\", \", \"[\", \"]\"))");
                } else {
                    format.append("$2T.hexDump($1L)");
                }

                if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                    format.append(" : \"null\")");
                }
                toString.addCode(format, a.name, ByteBufUtil.class, Collectors.class);
            } else {
                toString.addCode(a.name);
            }

            toString.addCode(" +").ln();

            // endregion
        }

        equals0.decIndent(2).addCode(';');

        hashCode.addStatement("return $L", type.hashCodeName);

        ValueAttribute last = type.generated.get(type.generated.size() - 1);
        if (unboxOptional(last, type) == STRING) {
            toString.addCode("\"'}\";");
        } else {
            toString.addCode("'}';");
        }
        toString.decIndent(2);

        if (singleton) {
            renderer.addMethod(boolean.class, "equals")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassRef.OBJECT, "o")
                    .addStatement("if (this == o) return true")
                    .addStatement("if (!(o instanceof $T)) return false", unknownTypeParams)
                    .addStatement("$1T $2L = ($1T) o", unknownTypeParams, type.equalsName)
                    .addStatement("return ID == $1L.identifier() && equalsTo($1L)", type.equalsName)
                    .complete();
        }
        equals0.complete();
        hashCode.complete();
        toString.complete();

        // endregion

        // region builder

        var builder = renderer.addType("Builder", ClassRenderer.Kind.CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addTypeVariables(type.typeVars);

        TypeRef bitsType = floorBitFlagsType(type.initBitsCount);

        if (type.initBitsCount > 0) {
            short pos = 0;
            for (ValueAttribute a : type.generated) {
                if (!a.flags.isEmpty()) {
                    continue;
                }

                builder.addField(bitsType, a.names().initBit, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("0x" + Integer.toHexString(1 << pos++))
                        .complete();
            }

            builder.addField(bitsType, "initBits", Modifier.PRIVATE)
                    .initializer("0x" + Integer.toHexString(~(0xffffffff << type.initBitsCount)))
                    .complete();
        }

        pending.add(builder.addConstructor(Modifier.PRIVATE));
        generateFrom(type, builder, pending);

        for (ValueAttribute a : type.attributes) {
            generateSetter(type, a, builder, renderer, pending);
        }

        pending.complete();

        var build = builder.addMethod(type.immutableType, "build", Modifier.PUBLIC);

        if (type.initBitsCount > 0) {
            var initializationIncomplete = builder.addMethod(
                    IllegalStateException.class, "initializationIncomplete", Modifier.PRIVATE)
                    .addStatement("$T<String> attributes = new $T<>($L(initBits))",
                            LIST, ArrayList.class, bitsCountMethod(type.initBitsCount));

            pending.add(initializationIncomplete);

            for (ValueAttribute a : type.generated) {
                if (!a.flags.isEmpty()) { // allow only mandatory fields
                    continue;
                }

                initializationIncomplete.addStatement("if ((initBits & $L) != 0) attributes.add($S)", a.names().initBit, a.name);
            }

            initializationIncomplete.addStatement("return new $T($S + attributes)",
                    IllegalStateException.class, "Cannot build " + type.baseType.rawType.name +
                            ", some of required attributes are not set: ");

            build.beginControlFlow("if (initBits != 0) {");
            build.addStatement("throw initializationIncomplete()");
            build.endControlFlow();
        }

        if (singleton) {
            build.addStatement("return canonize(new $T(this))", type.immutableType);
        } else {
            build.addStatement("return new $T(this)", type.immutableType);
        }

        build.complete();
        pending.complete();

        builder.complete();

        // endregion

        fileService.writeTo(renderer);
    }

    private void generateFrom(ValueType type, ClassRenderer<?> builder, CompletionDeferrer pending) {
        short optBits = 0;
        Map<String, Integer> commonMethods = new HashMap<>();
        Map<TypeRef, List<String>> typeToMethods = new HashMap<>();

        for (String name : type.superTypeMethodsNames) {
            commonMethods.put(name, null);
        }

        for (ValueAttribute a : type.attributes) {
            commonMethods.put(a.name, null);
        }

        for (TypeRef typeRef : type.interfaces) {
            TypeElement t = elements.getTypeElement(typeRef.getTypeName());
            Objects.requireNonNull(t, typeRef.getTypeName());
            List<String> methods = new ArrayList<>();

            for (Element e : t.getEnclosedElements()) {
                String name = e.getSimpleName().toString();
                // TODO: this is an unreliable check. Therefore we should not declare flags() methods in supertypes
                if (e.getKind() != ElementKind.METHOD || name.equals("identifier") ||
                    e.getModifiers().contains(Modifier.STATIC)) { // of()/instance()/builder()
                    continue;
                }

                Integer mask = commonMethods.get(name);
                if (mask == null && commonMethods.containsKey(name)) {
                    commonMethods.put(name, 1 << optBits++);
                }

                methods.add(name);
            }

            if (!methods.isEmpty()) {
                typeToMethods.put(typeRef, methods);
            }
        }

        boolean isPolymorphic = typeToMethods.size() + type.superTypeMethodsNames.size() > 0;
        var from = builder.addMethod(
                isPolymorphic ? PrimitiveTypeRef.VOID : type.builderType, "from");
        if (isPolymorphic) {
            if (optBits > Integer.SIZE) {
                throw new IllegalStateException();
            }

            from.addModifiers(Modifier.PRIVATE);
            from.addParameter(ClassRef.OBJECT, "instance");
            from.addStatement("$T.requireNonNull(instance)", Objects.class);
            if (optBits > 0) {
                from.addStatement("$T bits = 0", floorBitFlagsType(optBits));
            }

            // - interfaces
            // - base type or super type
            boolean first = true;
            int firstMask = 0;
            for (var e : typeToMethods.entrySet()) {
                pending.add(builder.addMethod(type.builderType, "from", Modifier.PUBLIC)
                        .addParameter(e.getKey(), "instance")
                        .addStatement("from((Object) instance)")
                        .addStatement("return this"));

                from.beginControlFlow("if (instance instanceof $T) {", e.getKey());
                from.addStatement("$1T c = ($1T) instance", e.getKey());

                for (String s : e.getValue()) {
                    Integer mask = commonMethods.get(s);
                    if (mask != null && !first) {
                        from.beginControlFlow("if ((bits & 0x$L) == 0) {", Integer.toHexString(mask));
                    }

                    from.addStatement("$1L(c.$1L())", s);

                    if (mask != null) {
                        if (!first) {
                            from.addStatement("bits |= 0x$L", Integer.toHexString(mask));
                            from.endControlFlow();
                        } else {
                            firstMask |= mask;
                        }
                    }
                }

                if (first) {
                    first = false;
                    if (firstMask > 0) {
                        from.addStatement("bits = 0x$L", Integer.toHexString(firstMask));
                    }
                }
                from.endControlFlow();
            }

            pending.add(builder.addMethod(type.builderType, "from", Modifier.PUBLIC)
                    .addParameter(type.baseType, "instance")
                    .addStatement("from((Object) instance)")
                    .addStatement("return this"));

            from.beginControlFlow("if (instance instanceof $T) {",
                    type.baseType.withTypeArguments(Collections.nCopies(
                            type.typeVars.size(), WildcardTypeRef.none())));
            from.addStatement("$1T c = ($1T) instance", type.baseType);

            for (ValueAttribute a : type.generated) {
                Integer mask = commonMethods.get(a.name);
                if (mask != null) {
                    from.beginControlFlow("if ((bits & 0x$L) == 0) {", Integer.toHexString(mask));
                }

                from.addStatement("$1L(c.$1L())", a.name);

                if (mask != null) {
                    from.addStatement("bits |= 0x$L", Integer.toHexString(mask));
                    from.endControlFlow();
                }
            }

            if (!type.superTypeMethodsNames.isEmpty()) {
                pending.add(builder.addMethod(type.builderType, "from", Modifier.PUBLIC)
                        .addParameter(type.superType, "instance")
                        .addStatement("from((Object) instance)")
                        .addStatement("return this"));

                from.nextControlFlow("} else if (instance instanceof $T) {", type.superType);
                from.addStatement("$1T c = ($1T) instance", type.superType);

                for (String s : type.superTypeMethodsNames) {
                    Integer mask = commonMethods.get(s);
                    if (mask != null && !first) {
                        from.beginControlFlow("if ((bits & 0x$L) == 0) {", Integer.toHexString(mask));
                    }

                    from.addStatement("$1L(c.$1L())", s);

                    if (mask != null) {
                        if (!first) {
                            from.addStatement("bits |= 0x$L", Integer.toHexString(mask));
                            from.endControlFlow();
                        } else {
                            firstMask |= mask;
                        }
                    }
                }

                if (first && firstMask > 0) {
                    from.addStatement("bits = 0x$L", Integer.toHexString(firstMask));
                }
            }
            from.endControlFlow();
        } else {
            from.addModifiers(Modifier.PUBLIC);
            from.addParameter(type.baseType, "instance");

            for (ValueAttribute a : type.generated) {
                from.addStatement("$1L(instance.$1L())", a.name);
            }
            from.addStatement("return this");
        }

        pending.add(from);
    }

    private void generateCopyParameters(ValueType type, ValueAttribute a,
                                        MethodRenderer<?> renderer, String paramName,
                                        String newValueVar) {
        renderer.addCode("return ");
        if (type.flags.contains(ValueType.Flag.SINGLETON)) {
            renderer.addCode("canonize(");
        }

        renderer.addCode("new $T(", type.immutableType);
        if (type.flags.contains(ValueType.Flag.NEED_STUB_PARAM)) {
            renderer.addCode("null, ");
        }

        for (int i = 0, n = type.generated.size(); i < n; i++) {
            ValueAttribute b = type.generated.get(i);

            String s;
            if (a == b) { // update param
                s = newValueVar;
            } else if (a.flags.contains(ValueAttribute.Flag.OPTIONAL) && b.name.equals(a.flagsName)) {
                s = newValue.apply(a.flagsName); // update flags
            } else {
                s = qualify(b, paramName);
            }

            String c = i != 0 ? ",$W " : "";
            renderer.addCode(c + "$L", s);
        }

        renderer.addCode(')');
        if (type.flags.contains(ValueType.Flag.SINGLETON)) {
            renderer.addCode(')');
        }
        renderer.addCode(';');
    }

    private void generateWither(ValueType type, ValueAttribute a,
                                TopLevelRenderer renderer, CompletionDeferrer pending) {

        TypeRef listElement = unwrap(a.type, LIST);

        TypeRef paramType = a.type;
        if (listElement != a.type) // Iterable<? extends ListElement>
            paramType = ParameterizedTypeRef.of(ITERABLE, expandBounds(listElement));
        if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) // @Nullable ParamType
            paramType = AnnotatedTypeRef.create(paramType, Nullable.class);

        String name = with.apply(a.name);
        String paramName = listElement != a.type ? "values" : "value";
        String localName = qualify(a, paramName);
        String newValueVar = newValue.apply(a.name);
        boolean transformed = false;

        var withMethod = renderer.addMethod(type.immutableType, name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(paramType, paramName);

        if (a.flags.contains(ValueAttribute.Flag.BIT_FLAG)) {
            // just delegate to with[flagsName] method
            withMethod.addStatement("return $L($T.mask($L, $L, $L))",
                    with.apply(a.flagsName()), UTILITY, a.flagsName(), a.flagMask(),
                    paramName);

            withMethod.complete();
            return;
        } else if (listElement != a.type) {
            transformed = true;

            if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
                withMethod.addStatement("if ($L == $L) return this", localName, paramName);

                if (listElement == BYTE_BUF) {
                    // implicit null-check in TlEncodingUtil.copyAsUnpooled()
                    withMethod.addStatement("$1T $2L = $3L != null ? $4T.stream($3L.spliterator(), false)$B" +
                                    ".map($5T::copyAsUnpooled)$B.collect($6T.toUnmodifiableList()) : null",
                            a.type, newValueVar, paramName, StreamSupport.class, UTILITY, Collectors.class);
                } else {
                    withMethod.addStatement("$1T $2L = $3L != null ? $4T.copyList($3L) : null",
                            a.type, newValueVar, paramName, UTILITY);

                }

                // re-compare references, maybe they are both equal to List.of()
                withMethod.addStatement("if ($L == $L) return this", localName, newValueVar);

                withMethod.addStatement("int $L = $T.mask($L, $L, $L != null)",
                        newValue.apply(a.flagsName), UTILITY, a.flagsName, a.flagMask, newValueVar);

                boolean canUnbox = listElement.safeUnbox() != listElement;
                if (canUnbox) {
                    listElement = listElement.safeUnbox();
                }
                var withVarargsMethod = renderer.addMethod(type.immutableType, name)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(AnnotatedTypeRef.create(listElement, Nullable.class), paramName, true)
                        .addStatement("if ($L == null && $L == null) return this", localName, paramName);

                if (listElement == BYTE_BUF) {
                    withVarargsMethod.addStatement("var $2L = $3L != null ? $6T.stream($3L)$B" +
                                    ".map($4T::copyAsUnpooled)$B.collect($5T.toUnmodifiableList()) : null",
                            a.type, newValueVar, paramName, UTILITY, Collectors.class, Arrays.class);
                } else if (canUnbox) {
                    withVarargsMethod.addStatement("var $1L = $3L != null ? $2T.stream($3L)$B.boxed()$B.collect($4T.toUnmodifiableList()) : null",
                            newValueVar, Arrays.class, paramName, Collectors.class);
                } else {
                    withVarargsMethod.addStatement("var $1L = $2L != null ? $3T.of($2L) : null", newValueVar, paramName, LIST);
                }

                withVarargsMethod.addStatement("if ($L == $L) return this", localName, newValueVar);
                withVarargsMethod.addStatement("int $L = $T.mask($L, $L, $L != null)",
                        newValue.apply(a.flagsName), UTILITY, a.flagsName, a.flagMask, newValueVar);

                generateCopyParameters(type, a, withVarargsMethod, paramName, newValueVar);

                pending.add(withVarargsMethod);
            } else {
                withMethod.addStatement("$T.requireNonNull($L)", Objects.class, paramName);
                withMethod.addStatement("if ($L == $L) return this", localName, paramName);

                if (listElement == BYTE_BUF) {
                    withMethod.addStatement("var $1L = $2T.stream($3L.spliterator(), false)$B" +
                                    ".map($4T::copyAsUnpooled)$B.collect($5T.toUnmodifiableList())",
                            newValueVar, StreamSupport.class, paramName, UTILITY, Collectors.class);
                } else {
                    withMethod.addStatement("$T $L = $T.copyList($L)", a.type, newValueVar, UTILITY, paramName);
                }

                withMethod.addStatement("if ($L == $L) return this", localName, newValueVar);

                boolean canUnbox = listElement.safeUnbox() != listElement;
                if (canUnbox) {
                    listElement = listElement.safeUnbox();
                }
                var withVarargsMethod = renderer.addMethod(type.immutableType, name)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(listElement, paramName, true)
                        .addStatement("$T.requireNonNull($L)", Objects.class, paramName);

                if (listElement == BYTE_BUF) {
                    withVarargsMethod.addStatement("var $1L = $2T.stream($3L)$B.map($4T::copyAsUnpooled)$B.collect($5T.toUnmodifiableList())",
                            newValueVar, Arrays.class, paramName, UTILITY, Collectors.class);
                } else if (canUnbox) {
                    withVarargsMethod.addStatement("var $L = $T.stream($L)$B.boxed()$B.collect($T.toUnmodifiableList())",
                            newValueVar, Arrays.class, paramName, Collectors.class);
                } else {
                    withVarargsMethod.addStatement("var $L = $T.of($L)", newValueVar, LIST, paramName);
                }

                withVarargsMethod.addStatement("if ($L == $L) return this", localName, newValueVar);

                generateCopyParameters(type, a, withVarargsMethod, paramName, newValueVar);

                pending.add(withVarargsMethod);
            }
        } else if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
            TypeRef unboxed = a.type.safeUnbox();

            BitSet bs;
            if (unboxed instanceof PrimitiveTypeRef && (bs = type.usedBits.get(a.flagsName)) != null && bs.get(a.flagPos)) {
                withMethod.addStatement("if ($T.equals($L, $L)) return this", Objects.class, localName, paramName);
            } else if (unboxed instanceof PrimitiveTypeRef) {
                transformed = true;

                withMethod.addStatement("if ($T.eq(($L & $L) != 0, $L, $L)) return this",
                        UTILITY, a.flagsName(), a.flagMask(), localName, paramName);

                withMethod.addStatement("$1T $2L = $4L != null ? $4L : $3L",
                        unboxed, newValueVar, defaultValueFor(unboxed), paramName);
            } else if (unboxed == BYTE_BUF) {
                transformed = true;

                withMethod.addStatement("if ($L == $L) return this", localName, paramName);
                withMethod.addStatement("$1T $2L = $4L != null ? $3T.copyAsUnpooled($4L) : null", BYTE_BUF, newValueVar, UTILITY, paramName);
            } else if (unboxed == STRING) {
                withMethod.addStatement("if ($T.equals($L, $L)) return this", Objects.class, localName, paramName);
            } else {
                withMethod.addStatement("if ($L == $L) return this", localName, paramName);
            }

            withMethod.addStatement("int $L = $T.mask($L, $L, $L != null)",
                    newValue.apply(a.flagsName()), UTILITY, a.flagsName(), a.flagMask(), paramName);
        } else if (a.type == PrimitiveTypeRef.DOUBLE) {
            withMethod.addStatement("if (Double.doubleToLongBits($L) == Double.doubleToLongBits($L)) return this", localName, paramName);
        } else if (a.type instanceof PrimitiveTypeRef) {
            if (a.flags.contains(ValueAttribute.Flag.BIT_SET)) {
                generateValueBitsMask(type, a, paramName, withMethod);
            }

            withMethod.addStatement("if ($L == $L) return this", localName, paramName);
        } else {
            withMethod.addStatement("$T.requireNonNull($L)", Objects.class, paramName);

            // Its implementation of the equals() method is fast enough
            if (a.type == STRING) {
                withMethod.addStatement("if ($L.equals($L)) return this", localName, paramName);
            } else if (a.type == BYTE_BUF && a.maxSize != -1) {
                withMethod.beginControlFlow("if ($L.readableBytes() != $L) {", paramName, a.maxSize);
                withMethod.addStatement("throw new IllegalArgumentException($S + $L.readableBytes() + $S)",
                        "size of value ", paramName, " != " + a.maxSize);
                withMethod.endControlFlow();

                withMethod.addStatement("if ($L.equals($L)) return this", localName, paramName);
            } else {
                withMethod.addStatement("if ($L == $L) return this", localName, paramName);
            }

            if (a.type == BYTE_BUF) {
                transformed = true;

                withMethod.addStatement("$T $L = $T.copyAsUnpooled($L)", BYTE_BUF, newValueVar, UTILITY, paramName);
            }
        }

        if (!transformed) {
            newValueVar = paramName;
        }

        generateCopyParameters(type, a, withMethod, paramName, newValueVar);
        withMethod.complete();
    }

    private void generateValueBitsMask(ValueType type, ValueAttribute a, String name, MethodRenderer<?> renderer) {
        StringJoiner s = new StringJoiner(" |$W ");
        for (int i = 0; i < type.generated.size(); i++) {
            ValueAttribute e = type.generated.get(i);

            BitSet bs;
            if (e.flags.contains(ValueAttribute.Flag.OPTIONAL) &&
                    a.name.equals(e.flagsName) && ((bs = type.usedBits.get(a.name)) == null ||
                    !bs.get(e.flagPos))) {
                s.add(e.flagMask);
            }
        }

        if (s.length() != 0) {
            renderer.addCode("$L &= ~(", name);
            renderer.addCodeFormatted(s.toString());
            renderer.addCode(");").ln();
        }
    }

    private void generateSetter(ValueType type, ValueAttribute a,
                                ClassRenderer<?> builder, TopLevelRenderer renderer,
                                CompletionDeferrer pending) {

        TypeRef listElement = unwrap(a.type, LIST);

        if (!a.flags.contains(ValueAttribute.Flag.BIT_FLAG)) {
            builder.addField(a.type, a.name, Modifier.PRIVATE).complete();
        }

        var setter = builder.addMethod(type.builderType, a.name);

        if (!a.flags.contains(ValueAttribute.Flag.BIT_FLAG)) {
            // necessary for our json deserialization
            var ann = renderer.createAnnotation(JsonSetter.class);
            if (a.jsonName != null) {
                ann.addAttribute(a.jsonName);
            }
            setter.addAnnotation(ann);
        }

        setter.addModifiers(Modifier.PUBLIC);

        if (a.flags.contains(ValueAttribute.Flag.BIT_FLAG)) {
            setter.addParameter(a.type, a.name);

            setter.addStatement("$1L = $4T.mask($1L, $2L, $3L)", a.flagsName, a.flagMask, a.name, UTILITY);
        } else if (listElement != a.type) {
            generateListSetters(type, a, setter, builder, pending);
        } else if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
            setter.addParameter(AnnotatedTypeRef.create(a.type, Nullable.class), a.name);

            setter.addStatement("this.$1L = $1L", a.name);
            setter.addStatement("$1L = $4T.mask($1L, $2L, $3L != null)", a.flagsName, a.flagMask, a.name, UTILITY);
        } else if (a.type instanceof PrimitiveTypeRef) {
            setter.addParameter(a.type, a.name);

            if (a.flags.contains(ValueAttribute.Flag.BIT_SET)) {
                generateValueBitsMask(type, a, a.name, setter);
            }

            setter.addStatement("this.$1L = $1L", a.name);

            // bitset fields is implicitly optional
            if (!a.flags.contains(ValueAttribute.Flag.BIT_SET)) {
                setter.addStatement("this.initBits &= ~$L", a.names().initBit);
            }
        } else { // reference type
            setter.addParameter(a.type, a.name);

            if (a.type == BYTE_BUF) {
                if (a.maxSize != -1) {
                    setter.beginControlFlow("if ($L.readableBytes() != $L) {", a.name, a.maxSize);
                    setter.addStatement("throw new IllegalArgumentException($S + $L.readableBytes() + $S)",
                            "size of value ", a.name, " != " + a.maxSize);
                    setter.endControlFlow();
                }

                setter.addStatement("this.$1L = $2T.copyAsUnpooled($1L)", a.name, UTILITY);
            } else {
                setter.addStatement("this.$1L = $2T.requireNonNull($1L)", a.name, Objects.class);
            }
            setter.addStatement("this.initBits &= ~$L", a.names().initBit);
        }

        setter.addStatement("return this");
        pending.add(setter);
    }

    private void generateListSetters(ValueType type, ValueAttribute a, MethodRenderer<?> setter,
                                     ClassRenderer<?> builder, CompletionDeferrer pending) {

        TypeRef listElement = unwrap(a.type, LIST);
        TypeRef iterableType = ParameterizedTypeRef.of(ITERABLE, expandBounds(listElement));
        boolean opt = a.flags.contains(ValueAttribute.Flag.OPTIONAL);
        boolean canUnbox = listElement.safeUnbox() != listElement;
        if (canUnbox) {
            listElement = listElement.safeUnbox();
        }

        // add methods

        String localNameSingular = qualify(a, "value");
        String localName = qualify(a, "values");

        var add = pending.add(builder.addMethod(type.builderType, a.names().add)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(listElement, "value"));
        if (!canUnbox) {
            add.addStatement("$T.requireNonNull(value)", Objects.class);
        }

        add.beginControlFlow("if ($L == null) {", localNameSingular);
        add.addStatement("$L = new $T<>()", localNameSingular, ArrayList.class);

        if (opt) {
            add.addStatement("$L |= $L", a.flagsName, a.flagMask);
        } else {
            add.addStatement("$L &= ~$L", type.initBitsName, a.names().initBit);
        }

        add.endControlFlow();

        var addv = pending.add(builder.addMethod(type.builderType, a.names().addv)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(listElement, "values", true));

        var addAll = pending.add(builder.addMethod(type.builderType, a.names().addAll)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(iterableType, "values"));

        String copyTransform = listElement == BYTE_BUF ? "$3T::copyAsUnpooled" : "$4T::requireNonNull";
        if (!canUnbox) {
            addv.addStatement("$1T copy = $2T.stream(values)$B.map(" + copyTransform + ")$B.collect($5T.toList())",
                    a.type, Arrays.class, UTILITY, Objects.class, Collectors.class);
        } else {
            addv.addStatement("$T copy = $T.stream(values)$B.boxed()$B.collect($T.toList())",
                    a.type, Arrays.class, Collectors.class);
        }
        addv.beginControlFlow("if ($L == null) {", localName);
        addv.addStatement("$L = copy", localName);
        if (opt) {
            addv.addStatement("$L |= $L", a.flagsName, a.flagMask);
        } else {
            addv.addStatement("$L &= ~$L", type.initBitsName, a.names().initBit);
        }
        addv.nextControlFlow("} else {");
        addv.addStatement("$L.addAll(copy)", localName);
        addv.endControlFlow();

        addAll.addStatement("$1T copy = $2T.stream(values.spliterator(), false)$B.map(" + copyTransform + ")$B.collect($5T.toList())",
                a.type, StreamSupport.class, UTILITY, Objects.class, Collectors.class);
        addAll.beginControlFlow("if ($L == null) {", localName);
        addAll.addStatement("$L = copy", localName);
        if (opt) {
            addAll.addStatement("$L |= $L", a.flagsName, a.flagMask);
        } else {
            addAll.addStatement("$L &= ~$L", type.initBitsName, a.names().initBit);
        }
        addAll.nextControlFlow("} else {");
        addAll.addStatement("$L.addAll(copy)", localName);
        addAll.endControlFlow();

        if (listElement == BYTE_BUF) {
            add.addStatement("$L.add($L.copyAsUnpooled(value))", localNameSingular, UTILITY);
        } else {
            add.addStatement("$L.add(value)", localNameSingular);
        }

        add.addStatement("return this");
        addv.addStatement("return this");
        addAll.addStatement("return this");

        // set method

        String setTransform = listElement == BYTE_BUF ? "$3T::copyAsUnpooled" : "$5T::requireNonNull";
        String copyCode = "$2T.stream(values.spliterator(), false)$B.map(" + setTransform + ")$B.collect($4T.toList())";
        if (opt) {
            setter.addParameter(AnnotatedTypeRef.create(iterableType, Nullable.class), "values")
                    .beginControlFlow("if (values == null) {")
                    .addStatement("$L = null", localName)
                    .addStatement("$L &= ~$L", a.flagsName, a.flagMask)
                    .addStatement("return this")
                    .endControlFlow()
                    .addStatement("$1L = " + copyCode, localName, StreamSupport.class, UTILITY, Collectors.class, Objects.class)
                    .addStatement("$L |= $L", a.flagsName, a.flagMask);
        } else {
            setter.addParameter(iterableType, "values")
                    .addStatement("$1L = " + copyCode, localName, StreamSupport.class, UTILITY, Collectors.class, Objects.class)
                    .addStatement("$L &= ~$L", type.initBitsName, a.names().initBit);
        }
    }

    // utilities

    private TypeRef expandBounds(TypeRef type) {
        if (type == STRING || type.safeUnbox() instanceof PrimitiveTypeRef) {
            return type;
        }
        return WildcardTypeRef.subtypeOf(type);
    }

    private String qualify(ValueAttribute a, String name) {
        return a.name.equals(name) ? "this." + a.name : a.name;
    }

    private String bitsCountMethod(int bits) {
        if (bits < Integer.SIZE) {
            return "Integer.bitCount";
        } else if (bits < Long.SIZE) {
            return "Long.bitCount";
        } else {
            throw new IllegalStateException(String.valueOf(bits));
        }
    }

    private TypeRef floorBitFlagsType(int bits) {
        if (bits < Byte.SIZE) {
            return PrimitiveTypeRef.BYTE;
        } else if (bits < Short.SIZE) {
            return PrimitiveTypeRef.SHORT;
        } else if (bits < Integer.SIZE) {
            return PrimitiveTypeRef.INT;
        } else if (bits < Long.SIZE) {
            return PrimitiveTypeRef.LONG;
        } else {
            throw new IllegalStateException(String.valueOf(bits));
        }
    }

    private TypeRef unboxOptional(ValueAttribute a, ValueType type) {
        if (a.flags.contains(ValueAttribute.Flag.OPTIONAL)) {
            BitSet bs = type.usedBits.get(a.flagsName);
            return bs == null || !bs.get(a.flagPos) ? a.type.safeUnbox() : a.type;
        }
        return a.type;
    }

    private String defaultValueFor(TypeRef type) {
        if (type == PrimitiveTypeRef.BOOLEAN)
            return "false";
        else if (type instanceof PrimitiveTypeRef)
            return "0";
        else
            return "null";
    }

    static TypeRef unwrap(TypeRef type, TypeRef pred) {
        ParameterizedTypeRef p;
        return type instanceof ParameterizedTypeRef &&
                !(p = (ParameterizedTypeRef) type).typeArguments.isEmpty() &&
                p.rawType.equals(pred)
                ? p.typeArguments.get(0)
                : type;
    }

    static class CompletionDeferrer {

        private final List<CompletableRenderer<?>> pending = new ArrayList<>();

        public <P, T extends CompletableRenderer<P>> T add(T renderer) {
            pending.add(renderer);
            return renderer;
        }

        public void complete() {
            pending.forEach(CompletableRenderer::complete);
            pending.clear();
        }
    }
}
