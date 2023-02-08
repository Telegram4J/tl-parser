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
