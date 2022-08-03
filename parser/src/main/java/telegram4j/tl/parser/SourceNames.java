package telegram4j.tl.parser;

import reactor.util.annotation.Nullable;

import javax.lang.model.SourceVersion;
import java.util.regex.Matcher;

import static telegram4j.tl.parser.SchemaGeneratorConsts.*;
import static telegram4j.tl.parser.Strings.camelize;

public final class SourceNames {

    private SourceNames() {
    }

    public static String normalizeName(String type) {
        type = applyNamingExceptions(type);

        Matcher vector = VECTOR_PATTERN.matcher(type);
        if (vector.matches()) {
            type = vector.group(1);
        }

        Matcher flag = FLAG_PATTERN.matcher(type);
        if (flag.matches()) {
            type = flag.group(3);
        }

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
        name = camelize(name);

        char f = name.charAt(0);
        if (Character.isUpperCase(f)) {
            name = Character.toLowerCase(f) + name.substring(1);
        }

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
}
