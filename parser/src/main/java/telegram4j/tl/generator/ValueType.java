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
    public final ParameterizedTypeRef jsonType;
    public final List<TypeVariableRef> typeVars;
    public final List<TypeVariableRef> typeVarNames;
    public final List<? extends TypeRef> interfaces;

    public ValueType(ClassRef baseType, List<TypeVariableRef> typeVars, List<? extends TypeRef> interfaces) {
        typeVarNames = typeVars.stream()
                .map(TypeVariableRef::withBounds)
                .collect(Collectors.toList());

        this.baseType = ParameterizedTypeRef.of(baseType, typeVarNames);
        this.typeVars = typeVars;
        this.interfaces = interfaces;

        immutableType = ParameterizedTypeRef.of(baseType.peer(immutable.apply(baseType.name)), typeVarNames);
        builderType = ParameterizedTypeRef.of(immutableType.rawType.nested("Builder"), typeVarNames);
        jsonType = ParameterizedTypeRef.of(immutableType.rawType.nested("Json"), typeVarNames);
    }

    public Map<String, BitSet> usedBits;

    public boolean needStubParam;
    public boolean canOmitCopyConstr;
    public boolean canOmitOfMethod;
    public List<ValueAttribute> attributes;
    public List<ValueAttribute> generated; // list without boolean bit flags
    public EnumSet<Flag> flags = EnumSet.noneOf(ValueType.Flag.class);
    public String identifier;
    public TypeRef superType;
    public Set<String> superTypeMethodsNames;
    public int initBitsCount;

    public String initBitsName;
    public String hashCodeName;
    public String equalsName;

    enum Flag {
        SINGLETON, // all fields are optional
        GENERIC_METHOD,
    }
}
