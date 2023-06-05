package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;

import javax.lang.model.SourceVersion;

import static telegram4j.tl.generator.Strings.camelize;

public final class SourceNames {

    private SourceNames() {
    }

    @Nullable
    public static String jacksonName(String name) {
        return name.indexOf('_') == -1 ? null : name;
    }

    public static String normalizeName(String type) {
        int dotIdx = type.lastIndexOf('.');
        if (dotIdx != -1) {
            type = type.substring(dotIdx + 1);
        }

        int spaceIdx = type.indexOf(' ');
        if (spaceIdx != -1) {
            type = type.substring(0, spaceIdx);
        }

        char first = type.charAt(0);
        if (Character.isLowerCase(first)) {
            type = Character.toUpperCase(first) + type.substring(1);
        }

        return camelize(type);
    }

    public static String formatFieldName(String name, @Nullable String type) {
        name = camelize(name, true);

        // TODO, replace
        if (!SourceVersion.isName(name)) {
            switch (name) {
                case "default", "static", "public", "final", "private", "short" -> {
                    if (!"true".equals(type)) {
                        throw new IllegalStateException("Non-flag parameter with java keyword name, type: " + type);
                    }
                    name = camelize("is_" + name);
                }
                case "long" -> {
                    if (!"double".equals(type)) {
                        throw new IllegalStateException("Non-flag parameter with java keyword name, type: " + type);
                    }

                    // just use the full form of word
                    name = "longitude";
                }
                default -> throw new IllegalStateException("Unhandled keyword use: " + name + ", type: " + type);
            }
        }

        return name;
    }

    public static String parentPackageName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        if (dot != -1) {
            return qualifiedName.substring(0, dot);
        }
        return "";
    }

    public static String escape(char c) {
        return switch (c) {
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\'' -> "\\'";
            case '\"' -> "\\\"";
            case '\\' -> "\\\\";
            default -> c >= ' ' && c <= '~' ? String.valueOf(c) : String.format("\\u%04x", (int) c);
        };
    }
}
