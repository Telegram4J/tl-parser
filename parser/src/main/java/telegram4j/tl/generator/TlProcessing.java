package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;
import telegram4j.tl.generator.renderer.ClassRef;
import telegram4j.tl.parser.TlTrees;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static telegram4j.tl.generator.SchemaGeneratorConsts.BASE_PACKAGE;
import static telegram4j.tl.generator.SchemaGeneratorConsts.METHOD_PACKAGE_PREFIX;
import static telegram4j.tl.generator.SourceNames.normalizeName;

public class TlProcessing {

    // utilities

    public static String parsePackageName(Configuration config, String rawType, boolean method) {
        StringJoiner pckg = new StringJoiner(".");
        pckg.add(BASE_PACKAGE);

        if (method) {
            pckg.add(METHOD_PACKAGE_PREFIX);
        }

        if (config.packagePrefix != null) {
            pckg.add(config.packagePrefix);
        }

        int dot = rawType.lastIndexOf('.');
        if (dot != -1) {
            pckg.add(rawType.substring(0, dot));
        }

        return pckg.toString();
    }

    // objects

    public static class Configuration {
        public final String name;
        @Nullable
        public final String packagePrefix;
        public final ClassRef superType;

        Configuration(String name, @Nullable String packagePrefix, @Nullable ClassRef superType) {
            this.name = name;
            this.packagePrefix = packagePrefix;
            this.superType = superType;
        }
    }

    public static class Type {
        public final Kind kind;
        public final TypeNameBase name;
        public final String id;
        public final List<Parameter> parameters;
        public final TypeName type;

        private Type(Kind kind, TypeNameBase name, String id, List<Parameter> parameters, TypeName type) {
            this.kind = kind;
            this.name = name;
            this.id = id;
            this.parameters = parameters;
            this.type = type;
        }

        public static Type parse(Configuration config, TlTrees.Type tlType) {
            var params = tlType.parameters().stream()
                    .map(p -> new Parameter(p.name(), TypeName.parse(config, p.type())))
                    .collect(Collectors.toList());
            TypeNameBase name = TypeNameBase.parse(config, tlType.name(), tlType.kind() == Kind.METHOD);
            TypeName type = TypeName.parse(config, tlType.type());
            return new Type(tlType.kind(), name, tlType.id(), params, type);
        }
    }

    public static class TypeNameBase {
        public final String packageName;
        public final String rawType;

        private String normalized;

        private TypeNameBase(String packageName, String rawType) {
            this.packageName = packageName;
            this.rawType = rawType;
        }

        public String normalized() {
            if (normalized != null) {
                return normalized;
            }
            return normalized = normalizeName(rawType);
        }

        public static TypeNameBase parse(Configuration config, String rawType, boolean method) {
            String packageName = parsePackageName(config, rawType, method);
            return new TypeNameBase(packageName, rawType);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (!(o instanceof TypeNameBase)) return false;
            TypeNameBase that = (TypeNameBase) o;
            return rawType.equals(that.rawType);
        }

        @Override
        public int hashCode() {
            return rawType.hashCode() + 2;
        }
    }

    public static class TypeName extends TypeNameBase {
        @Nullable
        public final TypeNameBase innerType;
        public final int flagPos;
        @Nullable
        public final String flagsName;

        public TypeName(String packageName, String rawType,
                        @Nullable TypeNameBase innerType, int flagPos,
                        @Nullable String flagsName) {
            super(packageName, rawType);
            this.innerType = innerType;
            this.flagPos = flagPos;
            this.flagsName = flagsName;
        }

        public static TypeName parse(Configuration config, String rawType) {
            String packageName = parsePackageName(config, rawType, false);

            TypeNameBase innerType = null;
            int flagPos = -1;
            String flagsName = null;
            Matcher matcher = SchemaGeneratorConsts.VECTOR_PATTERN.matcher(rawType);
            if (matcher.matches()) {
                innerType = TypeNameBase.parse(config, matcher.group(1), false);
            } else if ((matcher = SchemaGeneratorConsts.FLAG_PATTERN.matcher(rawType)).matches()) {
                flagsName = SourceNames.formatFieldName(matcher.group(1), null);
                flagPos = Integer.parseInt(matcher.group(2));
                innerType = parse(config, matcher.group(3));
            }

            return new TypeName(packageName, rawType, innerType, flagPos, flagsName);
        }

        public TypeNameBase innerType() {
            Objects.requireNonNull(innerType);
            return innerType;
        }

        public int flagPos() {
            if (flagPos == -1)
                throw new IllegalStateException();
            return flagPos;
        }

        public String flagsName() {
            Objects.requireNonNull(flagsName);
            return flagsName;
        }

        public boolean isFlag() {
            return flagPos != -1;
        }

        public boolean isBitFlag() {
            return isFlag() && innerType().rawType.equals("true");
        }

        public boolean isVector() {
            return innerType != null && flagPos == -1;
        }
    }

    public static class Parameter {
        public final String name;
        public final TypeName type;

        private String formattedName;

        public Parameter(String name, TypeName type) {
            this.name = name;
            this.type = type;
        }

        public String formattedName() {
            if (formattedName != null) {
                return formattedName;
            }
            return formattedName = SourceNames.formatFieldName(name, rawType0().rawType);
        }

        private TypeNameBase rawType0() {
            return type.innerType != null && type.isFlag() ? type.innerType : type;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Parameter parameter = (Parameter) o;
            return name.equals(parameter.name) &&
                    type.isFlag() == parameter.type.isFlag() &&
                    rawType0().rawType.equals(parameter.rawType0().rawType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, rawType0().rawType);
        }
    }
}
