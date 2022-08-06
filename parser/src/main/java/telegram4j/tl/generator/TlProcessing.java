package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;
import telegram4j.tl.parser.TlTrees;
import telegram4j.tl.parser.TlTrees.Type.Kind;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.type.TypeMirror;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static telegram4j.tl.generator.SchemaGeneratorConsts.methodPackagePrefix;
import static telegram4j.tl.generator.SourceNames.normalizeName;

public class TlProcessing {

    // utilities

    public static String parsePackageName(Configuration config, String rawType, boolean method) {
        StringJoiner pckg = new StringJoiner(".");
        pckg.add(config.basePackageName);

        if (method) {
            pckg.add(methodPackagePrefix);
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
        public final String basePackageName;
        public final String name;
        @Nullable
        public final String packagePrefix;
        public final TypeMirror superType;

        private Configuration(String basePackageName, String name,
                              @Nullable String packagePrefix, @Nullable TypeMirror superType) {
            this.basePackageName = basePackageName;
            this.name = name;
            this.packagePrefix = packagePrefix;
            this.superType = superType;
        }

        private static Map<String, ? extends AnnotationValue> asMap(AnnotationMirror mirror) {
            return mirror.getElementValues().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().getSimpleName().toString(), Map.Entry::getValue));
        }

        public static Configuration[] parse(String basePackageName, AnnotationMirror ann) {
            var attrs = asMap(ann);

            var value = Optional.ofNullable(attrs.get("value"))
                    .map(AnnotationValue::getValue)
                    .map(Configuration::<List<AnnotationMirror>>cast)
                    .map(l -> l.stream().map(Configuration::asMap).collect(Collectors.toList()))
                    .orElseThrow();

            Configuration[] configs = new Configuration[value.size()];
            for (int i = 0; i < configs.length; i++) {
                var config = value.get(i);

                String name = Optional.ofNullable(config.get("name"))
                        .map(AnnotationValue::getValue)
                        .map(Configuration::<String>cast)
                        .orElse(null);

                String packagePrefix = Optional.ofNullable(config.get("packagePrefix"))
                        .map(AnnotationValue::getValue)
                        .map(Configuration::<String>cast)
                        .orElse(null);

                TypeMirror superType = Optional.ofNullable(config.get("superType"))
                        .map(AnnotationValue::getValue)
                        .map(Configuration::<TypeMirror>cast)
                        .orElse(null);

                configs[i] = new Configuration(basePackageName, name, packagePrefix, superType);
            }

            return configs;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object o) {
            return (T) o;
        }

        @Override
        public String toString() {
            return "Configuration{" +
                    "basePackageName='" + basePackageName + '\'' +
                    ", name='" + name + '\'' +
                    ", packagePrefix='" + packagePrefix + '\'' +
                    ", superType=" + superType +
                    '}';
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
            return formattedName = SourceNames.formatFieldName(name, correctType0());
        }

        private String correctType0() {
            return (type.innerType != null && type.isFlag() ? type.innerType : type).rawType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, correctType0());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Parameter that = (Parameter) o;

            return name.equals(that.name) &&
                    type.isFlag() == that.type.isFlag() &&
                    correctType0().equals(that.correctType0());
        }
    }
}
