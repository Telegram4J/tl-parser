package telegram4j.tl.generator;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class NameDeduplicator implements Consumer<String>, Supplier<String> {
    private final String base;

    private byte counter;

    private NameDeduplicator(String base) {
        this.base = Objects.requireNonNull(base);
    }

    public static NameDeduplicator create(String base) {
        return new NameDeduplicator(base);
    }

    @Override
    public void accept(String s) {
        if ((base + counter).equals(s)) {
            counter++;
        }
    }

    @Override
    public String get() {
        return counter == 0 ? base : base + counter;
    }
}
