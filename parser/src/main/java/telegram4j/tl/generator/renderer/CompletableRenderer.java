package telegram4j.tl.generator.renderer;

public interface CompletableRenderer<P> {

    Stage stage();

    P complete();

    class Stage implements Comparable<Stage> {
        public static final Stage ANNOTATIONS = mandatory(-1, "ANNOTATIONS");
        public static final Stage MODIFIERS = mandatory(0, "MODIFIERS");
        public static final Stage TYPE_VARIABLES = mandatory(1, "TYPE_VARIABLES");
        public static final Stage PROCESSING = mandatory(Integer.MAX_VALUE - 1, "PROCESSING");
        public static final Stage COMPLETE = mandatory(Integer.MAX_VALUE, "COMPLETE");

        public final int index;
        public final String name;
        public final boolean optional;

        Stage(int index, String name, boolean optional) {
            this.index = index;
            this.name = name;
            this.optional = optional;
        }

        public static Stage optional(int index, String name) {
            return new Stage(index, name, true);
        }

        public static Stage mandatory(int index, String name) {
            return new Stage(index, name, false);
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
