package telegram4j.tl.generator.renderer;

import reactor.util.annotation.Nullable;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public final class TypeVariableRef implements TypeRef {
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
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof TypeVariableRef t)) return false;
        return name.equals(t.name) && bounds.equals(t.bounds);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + name.hashCode();
        h += (h << 5) + bounds.hashCode();
        return h;
    }

    // TODO implement toString
}
