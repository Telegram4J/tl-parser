package telegram4j.tl.generator.renderer;

public interface CompletableRenderer<P> {

    Stage stage();

    P complete();

    class Stage implements Comparable<Stage> {
        public static final Stage ANNOTATIONS = new Stage(-1, "ANNOTATIONS");
        public static final Stage MODIFIERS = new Stage(0, "MODIFIERS");
        public static final Stage TYPE_VARIABLES = new Stage(1, "TYPE_VARIABLES");
        public static final Stage PROCESSING = new Stage(Integer.MAX_VALUE - 1, "PROCESSING");
        public static final Stage COMPLETE = new Stage(Integer.MAX_VALUE, "COMPLETE");

        public final int index;
        public final String name;

        public Stage(int index, String name) {
            this.index = index;
            this.name = name;
        }

        @Override
        public int compareTo(Stage o) {
            return Integer.compare(index, o.index);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
