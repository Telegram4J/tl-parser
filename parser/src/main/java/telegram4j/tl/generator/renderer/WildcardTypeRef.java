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

import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.WildcardType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class WildcardTypeRef implements TypeRef {
    private static final WildcardTypeRef NONE = new WildcardTypeRef(null, null);

    @Nullable
    public final TypeRef upperBound;
    @Nullable
    public final TypeRef lowerBound;

    private WildcardTypeRef(@Nullable TypeRef upperBound, @Nullable TypeRef lowerBound) {
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    public static WildcardTypeRef none() {
        return NONE;
    }

    public static WildcardTypeRef subtypeOf(@Nullable TypeRef upperBound) {
        if (upperBound == null) {
            return NONE;
        }
        return new WildcardTypeRef(upperBound, null);
    }

    public static WildcardTypeRef subtypeOf(@Nullable Type upperBound) {
        if (upperBound == null) {
            return NONE;
        }
        return new WildcardTypeRef(TypeRef.of(upperBound), null);
    }

    public static WildcardTypeRef supertypeOf(@Nullable TypeRef lowerBound) {
        if (lowerBound == null) {
            return NONE;
        }
        return new WildcardTypeRef(null, lowerBound);
    }

    public static WildcardTypeRef supertypeOf(@Nullable Type lowerBound) {
        if (lowerBound == null) {
            return NONE;
        }
        return new WildcardTypeRef(null, TypeRef.of(lowerBound));
    }

    public boolean isNoBounds() {
        return upperBound == null && lowerBound == null;
    }

    public boolean isSubtypeBounded() {
        return upperBound != null;
    }

    public boolean isSuperTypeBounded() {
        return lowerBound != null;
    }

    protected String toString0(Function<TypeRef, String> toString) {
        if (lowerBound != null) {
            return "? super " + toString.apply(lowerBound);
        } else if (upperBound != null) {
            return "? extends " + toString.apply(upperBound);
        } else {
            return "?";
        }
    }

    @Override
    public String getTypeName() {
        return toString0(Type::getTypeName);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof WildcardTypeRef w)) return false;
        return Objects.equals(upperBound, w.upperBound) && Objects.equals(lowerBound, w.lowerBound);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(upperBound) + Objects.hashCode(lowerBound);
    }

    @Override
    public String toString() {
        return toString0(Type::toString);
    }
}
