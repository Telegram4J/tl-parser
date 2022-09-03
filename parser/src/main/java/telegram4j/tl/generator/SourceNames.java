package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;

import javax.lang.model.SourceVersion;

import static telegram4j.tl.generator.SchemaGeneratorConsts.namingExceptions;
import static telegram4j.tl.generator.Strings.camelize;

public final class SourceNames {

    private SourceNames() {
    }

    public static String normalizeName(String type) {
        type = applyNamingExceptions(type);

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
                case "default":
                case "static":
                case "public":
                case "final":
                case "private":
                    if (!"true".equals(type)) {
                        throw new IllegalStateException("Non-flag parameter with java keyword name, type: " + type);
                    }

                    name = camelize("is_" + name);
                    break;
                case "long":
                    if (!"double".equals(type)) {
                        throw new IllegalStateException("Non-flag parameter with java keyword name, type: " + type);
                    }

                    // just use the full form of word
                    name = "longitude";
                    break;
                default: throw new IllegalStateException("Unhandled keyword use: " + name + ", type: " + type);
            }
        }

        return name;
    }

    public static String applyNamingExceptions(String s) {
        String l = s;
        for (NameTransformer t : namingExceptions) {
            l = t.apply(l);
        }
        return l;
    }

    public static String parentPackageName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        if (dot != -1) {
            return qualifiedName.substring(0, dot);
        }
        return "";
    }

    public static String escape(char c) {
        switch (c) {
            case '\b': return "\\b";
            case '\f': return "\\f";
            case '\n': return "\\n";
            case '\r': return "\\r";
            case '\t': return "\\t";
            case '\'': return "\\'";
            case '\"': return "\\\"";
            case '\\': return "\\\\";
            default: return c >= ' ' && c <= '~' ? String.valueOf(c) : String.format("\\u%04x", (int) c);
        }
    }
}
