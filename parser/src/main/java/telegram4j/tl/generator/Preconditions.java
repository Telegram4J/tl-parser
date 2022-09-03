package telegram4j.tl.generator;

import java.util.function.Supplier;

public class Preconditions {

    public static void requireArgument(boolean state, String text) {
        if (!state) {
            throw new IllegalArgumentException(text);
        }
    }

    public static void requireArgument(boolean state, Supplier<String> text) {
        if (!state) {
            throw new IllegalArgumentException(text.get());
        }
    }

    public static void requireState(boolean state, String text) {
        if (!state) {
            throw new IllegalStateException(text);
        }
    }

    public static void requireState(boolean state, Supplier<String> text) {
        if (!state) {
            throw new IllegalStateException(text.get());
        }
    }
}
