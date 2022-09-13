package telegram4j.tl.generator.renderer;

abstract class BaseCompletableRenderer<P extends BaseClassRenderer<?>> implements CompletableRenderer<P> {
    protected final P parent;
    protected final CharSink out;

    protected Stage stage;

    protected BaseCompletableRenderer(P parent, Stage stage) {
        this.parent = parent;
        this.stage = stage;

        out = parent.out.createChild();
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public P complete() {
        if (stage != Stage.COMPLETE) {
            complete0();
            parent.complete(this);
            stage = Stage.COMPLETE;
        }
        return parent;
    }

    protected abstract void complete0();
}
