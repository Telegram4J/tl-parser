package telegram4j.tl.generator.renderer;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ParameterizedTypeRef implements TypeRef {
    public final ClassRef rawType;
    public final List<TypeRef> typeArguments;

    private ParameterizedTypeRef(ClassRef rawType, List<TypeRef> typeArguments) {
        this.rawType = Objects.requireNonNull(rawType);
        this.typeArguments = Objects.requireNonNull(typeArguments);
    }

    public static ParameterizedTypeRef of(ClassRef rawType, Collection<? extends TypeRef> typeArguments) {
        return new ParameterizedTypeRef(rawType, List.copyOf(typeArguments));
    }

    public static ParameterizedTypeRef of(ClassRef rawType, TypeRef... typeArguments) {
        return new ParameterizedTypeRef(rawType, List.of(typeArguments));
    }

    public static ParameterizedTypeRef of(Class<?> rawType, Type... typeArguments) {
        return new ParameterizedTypeRef(ClassRef.of(rawType), Arrays.stream(typeArguments)
                .map(TypeRef::of)
                .collect(Collectors.toUnmodifiableList()));
    }

    public ParameterizedTypeRef withTypeArguments(Collection<? extends TypeRef> typeArguments) {
        if (this.typeArguments == typeArguments) return this;
        return new ParameterizedTypeRef(rawType, List.copyOf(typeArguments));
    }

    @Override
    public String getTypeName() {
        StringBuilder out = new StringBuilder(rawType.getTypeName());
        if (!typeArguments.isEmpty()) {
            out.append('<');
            for (int i = 0, n = typeArguments.size(); i < n; i++) {
                out.append(typeArguments.get(i).getTypeName());
                if (i != n - 1) {
                    out.append(", ");
                }
            }
            out.append('>');
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterizedTypeRef that = (ParameterizedTypeRef) o;
        return rawType.equals(that.rawType) && typeArguments.equals(that.typeArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawType, typeArguments);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(rawType.toString());
        if (!typeArguments.isEmpty()) {
            out.append('<');
            for (int i = 0, n = typeArguments.size(); i < n; i++) {
                out.append(typeArguments.get(i));
                if (i != n - 1) {
                    out.append(", ");
                }
            }
            out.append('>');
        }
        return out.toString();
    }
}
