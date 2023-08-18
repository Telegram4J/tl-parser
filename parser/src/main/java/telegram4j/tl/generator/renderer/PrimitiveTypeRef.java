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

import java.util.Locale;

public enum PrimitiveTypeRef implements TypeRef {
    BOOLEAN(ClassRef.BOOLEAN),
    BYTE(ClassRef.BYTE),
    CHAR(ClassRef.CHARACTER),
    DOUBLE(ClassRef.DOUBLE),
    FLOAT(ClassRef.FLOAT),
    INT(ClassRef.INTEGER),
    LONG(ClassRef.LONG),
    SHORT(ClassRef.SHORT),
    VOID(ClassRef.VOID);

    public final ClassRef boxed;

    PrimitiveTypeRef(ClassRef boxed) {
        this.boxed = boxed;
    }

    @Override
    public TypeRef safeBox() {
        return boxed;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.US);
    }
}
