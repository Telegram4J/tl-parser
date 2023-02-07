package telegram4j.tl.generator.renderer;

import reactor.util.annotation.Nullable;
import telegram4j.tl.generator.Preconditions;

import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ArrayRef implements TypeRef {
    public final TypeRef component;
    public final short dimensions;

    private ArrayRef(TypeRef component, short dimensions) {
        this.component = component;
        this.dimensions = dimensions;
    }

    public static ArrayRef of(Type component, short dimensions) {
        if (component instanceof ArrayRef c) {
            dimensions = (short) Math.addExact(c.dimensions, dimensions);
            component = c.component;
        }

        Preconditions.requireArgument(dimensions >= 1 && dimensions <= 255, "Dimension must be between [1, 255]");
        return new ArrayRef(TypeRef.of(component), dimensions);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ArrayRef a)) return false;
        return dimensions == a.dimensions && component.equals(a.component);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + component.hashCode();
        h += (h << 5) + dimensions;
        return h;
    }

    @Override
    public String getTypeName() {
        return component.getTypeName() + "[]".repeat(dimensions);
    }

    @Override
    public String toString() {
        return component + "[]".repeat(dimensions);
    }
}
