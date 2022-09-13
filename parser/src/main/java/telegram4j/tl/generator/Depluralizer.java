package telegram4j.tl.generator;

import java.util.function.UnaryOperator;

public class Depluralizer implements UnaryOperator<String> {
    private static final Naming NAMING_S_PLURAL = Naming.from("*s");
    private static final Naming NAMING_IES_PLURAL = Naming.from("*ies");

    private static final Depluralizer instance = new Depluralizer();

    private Depluralizer() {}

    public static Depluralizer instance() {
        return instance;
    }

    @Override
    public String apply(String s) {
        String detected = NAMING_IES_PLURAL.detect(s);
        if (detected != null) {
            return detected + 'y';
        }
        detected = NAMING_S_PLURAL.detect(s);
        if (detected != null) {
            return detected;
        }
        return s;
    }
}
