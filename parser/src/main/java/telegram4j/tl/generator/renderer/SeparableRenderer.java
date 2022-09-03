package telegram4j.tl.generator.renderer;

public interface SeparableRenderer<P> extends CompletableRenderer<P> {

    <T extends CompletableRenderer<?>> boolean separate(T child);
}
