package telegram4j.tl.generator.renderer;

import reactor.util.annotation.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AnnotatedTypeRef implements TypeRef {

    public final TypeRef type;
    public final List<ClassRef> annotations;

    private AnnotatedTypeRef(TypeRef type, List<ClassRef> annotations) {
        this.type = Objects.requireNonNull(type);
        this.annotations = Objects.requireNonNull(annotations);
    }

    public List<ClassRef> annotations() {
        return annotations;
    }

    public static AnnotatedTypeRef create(Type type, Class<? extends Annotation> annotation) {
        return create(type, List.of(annotation));
    }

    public static AnnotatedTypeRef create(Type type, Collection<Class<? extends Annotation>> annotations) {
        Objects.requireNonNull(type);
        if (type instanceof AnnotatedTypeRef c) {

            return new AnnotatedTypeRef(c.type, Stream.concat(c.annotations.stream(), annotations.stream()
                            .map(ClassRef::of))
                    .collect(Collectors.toUnmodifiableList()));
        }

        return new AnnotatedTypeRef(TypeRef.of(type), annotations.stream()
                .map(ClassRef::of)
                .collect(Collectors.toUnmodifiableList()));
    }

    @SafeVarargs
    public final AnnotatedTypeRef withAnnotations(Class<? extends Annotation>... annotations) {
        return new AnnotatedTypeRef(type, Arrays.stream(annotations)
                .map(ClassRef::of)
                .collect(Collectors.toUnmodifiableList()));
    }

    @Override
    public String getTypeName() {
        StringBuilder ann = new StringBuilder();
        for (ClassRef annotation : annotations) {
            ann.append('@');
            ann.append(annotation.getTypeName());
            ann.append(' ');
        }
        ann.append(type.getTypeName());
        return ann.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AnnotatedTypeRef that = (AnnotatedTypeRef) o;
        return type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder ann = new StringBuilder();
        for (ClassRef annotation : annotations) {
            ann.append('@');
            ann.append(annotation);
            ann.append(' ');
        }
        ann.append(type);
        return ann.toString();
    }
}
