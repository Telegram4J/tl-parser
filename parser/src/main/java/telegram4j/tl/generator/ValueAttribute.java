package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;
import telegram4j.tl.generator.renderer.TypeRef;

import java.util.EnumSet;
import java.util.Objects;

import static telegram4j.tl.generator.SchemaGeneratorConsts.*;

class ValueAttribute {
    public final String name;

    public TypeRef type;
    public EnumSet<Flag> flags = EnumSet.noneOf(ValueAttribute.Flag.class);
    @Nullable
    public String flagsName;
    @Nullable
    public String flagMask;
    public int flagPos = -1;
    @Nullable
    public String jsonName;
    public short maxSize = -1; // size restriction in bytes; used only for int256 and int128

    @Nullable
    private Names names;

    ValueAttribute(String name) {
        this.name = name;
    }

    public Names names() {
        if (names == null) {
            names = new Names();
        }
        return names;
    }

    public String flagsName() {
        Objects.requireNonNull(flagsName);
        return flagsName;
    }

    public String flagMask() {
        Objects.requireNonNull(flagMask);
        return flagMask;
    }

    class Names {
        public final String singular = Depluralizer.instance().apply(name);
        public final String initBit = Style.initBit.apply(name, Naming.As.SCREMALIZED);
        public final String add = Style.add.apply(singular);
        public final String addv = Style.add.apply(name);
        public final String set = Style.set.apply(name);
        public final String addAll = Style.addAll.apply(name);
    }

    enum Flag {
        BIT_FLAG,
        BIT_SET, // implicitly optional attribute
        OPTIONAL
    }
}
