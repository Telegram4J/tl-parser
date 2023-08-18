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

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ParameterizedTypeRef implements TypeRef {
    public final ClassRef rawType;
    public final List<TypeRef> typeArguments;

    private ParameterizedTypeRef(ClassRef rawType, List<TypeRef> typeArguments) {
        this.rawType = Objects.requireNonNull(rawType);
        this.typeArguments = Objects.requireNonNull(typeArguments);
    }

    public static ParameterizedTypeRef of(ClassRef rawType, Collection<? extends TypeRef> typeArguments) {
        return new ParameterizedTypeRef(rawType, List.copyOf(typeArguments));
    }

    public static ParameterizedTypeRef of(ClassRef rawType, TypeRef... typeArguments) {
        return new ParameterizedTypeRef(rawType, List.of(typeArguments));
    }

    public static ParameterizedTypeRef of(Class<?> rawType, Type... typeArguments) {
        return new ParameterizedTypeRef(ClassRef.of(rawType), Arrays.stream(typeArguments)
                .map(TypeRef::of)
                .collect(Collectors.toUnmodifiableList()));
    }

    public ParameterizedTypeRef withTypeArguments(Collection<? extends TypeRef> typeArguments) {
        if (this.typeArguments == typeArguments) return this;
        return new ParameterizedTypeRef(rawType, List.copyOf(typeArguments));
    }

    @Override
    public String getTypeName() {
        StringBuilder out = new StringBuilder(rawType.getTypeName());
        if (!typeArguments.isEmpty()) {
            out.append('<');
            for (int i = 0, n = typeArguments.size(); i < n; i++) {
                out.append(typeArguments.get(i).getTypeName());
                if (i != n - 1) {
                    out.append(", ");
                }
            }
            out.append('>');
        }
        return out.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ParameterizedTypeRef p)) return false;
        return rawType.equals(p.rawType) && typeArguments.equals(p.typeArguments);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + rawType.hashCode();
        h += (h << 5) + typeArguments.hashCode();
        return h;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(rawType.toString());
        if (!typeArguments.isEmpty()) {
            out.append('<');
            for (int i = 0, n = typeArguments.size(); i < n; i++) {
                out.append(typeArguments.get(i));
                if (i != n - 1) {
                    out.append(", ");
                }
            }
            out.append('>');
        }
        return out.toString();
    }
}
