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

import javax.lang.model.type.TypeMirror;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Objects;

public sealed interface TypeRef extends Type
        permits AnnotatedTypeRef, ArrayRef, ClassRef, ParameterizedTypeRef,
                PrimitiveTypeRef, TypeVariableRef, WildcardTypeRef {

    default TypeRef safeBox() {
        return this;
    }

    default TypeRef safeUnbox() {
        return this;
    }

    static TypeRef from(TypeMirror typeMirror) {
        return RenderUtils.from(typeMirror);
    }

    static TypeRef of(Type type) {
        Objects.requireNonNull(type);
        if (type instanceof TypeRef t) {
            return t;
        } else if (type instanceof Class<?> klass) {
            if (klass.isArray()) {
                Class<?> cl = klass;
                short dimensions = 0;
                do {
                    dimensions++;
                    cl = cl.getComponentType();
                } while (cl.isArray());

                return ArrayRef.of(of(cl), dimensions);
            } else if (klass.isPrimitive()) {
                return PrimitiveTypeRef.valueOf(klass.getName().toUpperCase(Locale.US));
            }
            return ClassRef.of(klass);
        }

        throw new IllegalArgumentException("Unexpected type: " + type);
    }
}
