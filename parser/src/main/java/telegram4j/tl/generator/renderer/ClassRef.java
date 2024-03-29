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
package telegram4j.tl.generator.renderer;

import reactor.util.annotation.Nullable;
import telegram4j.tl.generator.Preconditions;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.SimpleElementVisitor9;

public final class ClassRef implements TypeRef {
    public static final ClassRef BOOLEAN = create("Boolean");
    public static final ClassRef BYTE = create("Byte");
    public static final ClassRef CHARACTER = create("Character");
    public static final ClassRef DOUBLE = create("Double");
    public static final ClassRef FLOAT = create("Float");
    public static final ClassRef INTEGER = create("Integer");
    public static final ClassRef LONG = create("Long");
    public static final ClassRef SHORT = create("Short");
    public static final ClassRef VOID = create("Void");

    public static final ClassRef OBJECT = create("Object");

    public final String packageName;
    public final String name;
    @Nullable
    public final ClassRef enclosing;

    private ClassRef(String packageName, String name, @Nullable ClassRef enclosing) {
        this.packageName = packageName;
        this.name = name;
        this.enclosing = enclosing;
    }

    private static ClassRef create(String name) {
        return new ClassRef( "java.lang", name, null);
    }

    public static ClassRef of(Class<?> klass) {
        Preconditions.requireArgument(!klass.isPrimitive(), "Primitive types can not be represented as a ClassRef");
        Preconditions.requireArgument(!klass.isArray(), "Array types can not be represented as a ClassRef");

        StringBuilder anonymousSuffix = new StringBuilder();
        int lastDollar;
        while (klass.isAnonymousClass()) {
            lastDollar = klass.getName().lastIndexOf('$');
            anonymousSuffix.insert(0, klass.getName().substring(lastDollar));
            klass = klass.getEnclosingClass();
        }

        String name = klass.getSimpleName() + anonymousSuffix;
        if (klass.getEnclosingClass() == null) {
            int lastDot = klass.getName().lastIndexOf('.');
            String packageName = lastDot != -1 ? klass.getName().substring(0, lastDot) : "";
            return of(packageName, name);
        }

        return of(klass.getEnclosingClass()).nested(name);
    }

    public static ClassRef from(TypeElement typeElement) {
        String simpleName = typeElement.getSimpleName().toString();

        class ClassRefVisitor extends SimpleElementVisitor9<ClassRef, Void> {
            @Override
            public ClassRef visitPackage(PackageElement packageElement, Void p) {
                return new ClassRef(packageElement.getQualifiedName().toString(), simpleName, null);
            }

            @Override
            public ClassRef visitType(TypeElement enclosingClass, Void p) {
                return from(enclosingClass).nested(simpleName);
            }

            @Override
            public ClassRef defaultAction(Element enclosingElement, Void p) {
                throw new IllegalArgumentException();
            }
        }

        return typeElement.getEnclosingElement().accept(new ClassRefVisitor(), null);
    }

    public static ClassRef of(String packageName, String name) {
        if (packageName.equals("java.lang")) {
            switch (name) {
                case "Boolean": return BOOLEAN;
                case "Byte": return BYTE;
                case "Character": return CHARACTER;
                case "Double": return DOUBLE;
                case "Float": return FLOAT;
                case "Integer": return INTEGER;
                case "Long": return LONG;
                case "Short": return SHORT;
                case "Void": return VOID;
            }
        }
        return new ClassRef(packageName, name, null);
    }

    public static ClassRef of(String packageName, String first, String... rest) {
        if (rest.length == 0) {
            return of(packageName, first);
        }

        ClassRef type = new ClassRef(packageName, first, null);
        for (String name : rest) {
            type = type.nested(name);
        }
        return type;
    }

    @Override
    public TypeRef safeUnbox() {
        if (!isBoxedPrimitive()) {
            return this;
        }
        return unbox();
    }

    public boolean isBoxedPrimitive() {
        return equals(BOOLEAN) || equals(BYTE) || equals(CHARACTER) ||
                equals(DOUBLE) || equals(FLOAT) || equals(INTEGER) ||
                equals(LONG) || equals(SHORT) || equals(VOID);
    }

    public PrimitiveTypeRef unbox() {
        Preconditions.requireState(packageName.equals("java.lang"), "Package name must be 'java.lang'");

        return switch (name) {
            case "Boolean" -> PrimitiveTypeRef.BOOLEAN;
            case "Byte" -> PrimitiveTypeRef.BYTE;
            case "Character" -> PrimitiveTypeRef.CHAR;
            case "Double" -> PrimitiveTypeRef.DOUBLE;
            case "Float" -> PrimitiveTypeRef.FLOAT;
            case "Integer" -> PrimitiveTypeRef.INT;
            case "Long" -> PrimitiveTypeRef.LONG;
            case "Short" -> PrimitiveTypeRef.SHORT;
            case "Void" -> PrimitiveTypeRef.VOID;
            default -> throw new IllegalStateException("Unexpected value: '" + name + "'");
        };
    }

    public ClassRef peer(String name) {
        return new ClassRef(packageName, name, enclosing);
    }

    public ClassRef nested(String name) {
        return new ClassRef(packageName, name, this);
    }

    public ClassRef topLevel() {
        if (enclosing == null) {
            return this;
        }

        ClassRef enc = enclosing;
        ClassRef prev = enclosing;

        while (true) {
            if (enc == null) {
                return prev;
            }

            prev = enc;
            enc = enc.enclosing;
        }
    }

    public String qualifiedName() {
        if (packageName.isEmpty()) {
            return name;
        }
        if (enclosing != null) {
            return enclosing.qualifiedName() + '.' + name;
        }
        return packageName + '.' + name;
    }

    @Override
    public String getTypeName() {
        return qualifiedName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassRef c)) return false;
        return qualifiedName().equals(c.qualifiedName());
    }

    @Override
    public int hashCode() {
        return qualifiedName().hashCode();
    }

    @Override
    public String toString() {
        if (enclosing == null) {
            return name;
        }
        return enclosing.name + '.' + name;
    }
}
