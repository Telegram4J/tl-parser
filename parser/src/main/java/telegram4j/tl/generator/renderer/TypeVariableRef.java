package telegram4j.tl.generator.renderer;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class TypeVariableRef implements TypeRef {
    public final String name;
    public final List<TypeRef> bounds;

    private TypeVariableRef(String name, List<TypeRef> bounds) {
        this.name = Objects.requireNonNull(name);
        this.bounds = Objects.requireNonNull(bounds);
    }

    public static TypeVariableRef of(String name) {
        return new TypeVariableRef(name, List.of());
    }

    public static TypeVariableRef of(String name, TypeRef... bounds) {
        return new TypeVariableRef(name, List.of(bounds));
    }

    public static TypeVariableRef of(String name, Collection<? extends TypeRef> bounds) {
        return new TypeVariableRef(name, List.copyOf(bounds));
    }

    public static TypeVariableRef of(String name, Type... bounds) {
        return new TypeVariableRef(name, Arrays.stream(bounds)
                .map(TypeRef::of)
                .collect(Collectors.toUnmodifiableList()));
    }

    public TypeVariableRef withBounds(Type... bounds) {
        return new TypeVariableRef(name, Arrays.stream(bounds)
                .map(TypeRef::of)
                .collect(Collectors.toUnmodifiableList()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypeVariableRef that = (TypeVariableRef) o;
        return name.equals(that.name) && bounds.equals(that.bounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, bounds);
    }

    // TODO implement toString
}
