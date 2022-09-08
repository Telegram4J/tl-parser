package telegram4j.tl.generator.renderer;

import java.lang.reflect.Type;
import java.util.Collection;

public interface AnnotatedRenderer<P> extends CompletableRenderer<P> {

    AnnotatedRenderer<P> addAnnotation(AnnotationRenderer renderer);

    AnnotatedRenderer<P> addAnnotations(Type... annotations);

    AnnotatedRenderer<P> addAnnotations(Collection<? extends Type> annotations);
}
