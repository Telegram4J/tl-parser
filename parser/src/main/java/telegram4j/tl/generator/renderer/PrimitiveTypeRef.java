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
