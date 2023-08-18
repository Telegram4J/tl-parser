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


import telegram4j.tl.generator.renderer.ClassRef;
import telegram4j.tl.generator.renderer.ParameterizedTypeRef;
import telegram4j.tl.generator.renderer.TypeRef;
import telegram4j.tl.generator.renderer.TypeVariableRef;

import java.util.*;
import java.util.stream.Collectors;

import static telegram4j.tl.generator.SchemaGeneratorConsts.Style.immutable;

class ValueType {
    public final ParameterizedTypeRef baseType;
    public final ParameterizedTypeRef immutableType;
    public final ParameterizedTypeRef builderType;
    public final List<TypeVariableRef> typeVars;
    public final List<TypeVariableRef> typeVarNames;

    public Map<String, BitSetInfo> bitSets = new HashMap<>();

    public List<ValueAttribute> attributes;
    public List<ValueAttribute> generated; // list without boolean bit flags
    public EnumSet<Flag> flags = EnumSet.noneOf(ValueType.Flag.class);
    public String identifier;
    public TypeRef superType;
    public List<String> superTypeMethodsNames;
    public short initBitsCount;

    public String initBitsName;

    public ValueType(ClassRef baseType, List<TypeVariableRef> typeVars) {
        typeVarNames = typeVars.stream()
                .map(TypeVariableRef::withBounds)
                .collect(Collectors.toList());

        this.baseType = ParameterizedTypeRef.of(baseType, typeVarNames);
        this.typeVars = typeVars;

        immutableType = ParameterizedTypeRef.of(baseType.peer(immutable.apply(baseType.name)), typeVarNames);
        builderType = ParameterizedTypeRef.of(immutableType.rawType.nested("Builder"), typeVarNames);
    }

    enum Flag {
        SINGLETON, // all fields are optional
        NEED_STUB_PARAM,
        CAN_OMIT_COPY_CONSTRUCTOR,
        CAN_OMIT_OF_METHOD
    }

    static class BitSetInfo {
        public StringJoiner valuesMask = new StringJoiner(" |$W ");
        public short bitFlagsCount;
        // pos->count
        public Map<Integer, Counter> bitUsage = new HashMap<>();
    }
}
