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

        if (SourceVersion.isName(name)) {
            return name;
        }

        // A keyword can't be a method name, so derive another one from the type
        if ("true".equals(type)) {
            return camelize("is_" + name);
        }
        if (name.equals("long") && "double".equals(type)) {
            // just use the full form of word
            return "longitude";
        }
        if (type != null && isNamingType(type)) {
            String typeName = normalizeName(type);
            return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
        }
        throw new IllegalStateException("Unhandled keyword use: " + name + ", type: " + type);
    }

    // Boxed types, whose name says what the value is, e.g. PaidReactionPrivacy or storage.FileType,
    // as opposed to primitives, vectors, generics and Bool/Object/JSONValue
    private static boolean isNamingType(String type) {
        return switch (type) {
            case "Bool", "Object", "JSONValue", "X" -> false;
            default -> type.matches("([a-z]\\w*\\.)?[A-Z]\\w*");
        };
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
