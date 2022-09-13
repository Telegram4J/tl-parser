package telegram4j.tl.generator.renderer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;

public interface AnnotatedRenderer<P> extends CompletableRenderer<P> {

    AnnotatedRenderer<P> addAnnotation(AnnotationRenderer renderer);

    AnnotatedRenderer<P> addAnnotation(Class<? extends Annotation> annotation);

    AnnotatedRenderer<P> addAnnotations(Iterable<Class<? extends Annotation>> annotations);
}
