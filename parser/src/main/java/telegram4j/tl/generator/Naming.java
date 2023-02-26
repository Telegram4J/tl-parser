package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public abstract class Naming implements UnaryOperator<String> {

    private static final String NAME_PLACEHOLDER = "*";
    private static final Pattern NAME_PATTERN = Pattern.compile(Pattern.quote(NAME_PLACEHOLDER));

    @Override
    public String apply(String input) {
        return apply(input, As.IDENTICAL);
    }

    public abstract String apply(String input, As as);

    public enum As {
        IDENTICAL,
        SCREMALIZED
    }

    public static Naming from(String template) {
        if (template.isEmpty() || template.equals(NAME_PLACEHOLDER)) {
            return IDENTITY_NAMING;
        }

        String[] parts = NAME_PATTERN.split(template, 3);
        Preconditions.requireState(parts.length <= 2, "Template '" + template
                + "' contains more than one '*' placeholder");

        if (parts.length == 1) {
            return new ConstantNaming(template);
        }
        return new PrefixSuffixNaming(parts[0], parts[1]);
    }

    public static Naming identity() {
        return IDENTITY_NAMING;
    }

    @Nullable
    public abstract String detect(String identifier);

    private static final Naming IDENTITY_NAMING = new Naming() {
        @Override
        public String apply(String input, As as) {
            return switch (as) {
                case IDENTICAL -> input;
                case SCREMALIZED -> Strings.screamilize(input);
            };
        }

        @Override
        public String detect(String identifier) {
            return identifier;
        }

        @Override
        public String toString() {
            return NAME_PLACEHOLDER;
        }
    };

    private static class ConstantNaming extends Naming {
        final String name;

        ConstantNaming(String name) {
            this.name = name;
        }

        @Override
        public String apply(String input, As as) {
            return switch (as) {
                case IDENTICAL -> name;
                case SCREMALIZED -> Strings.screamilize(name);
            };
        }

        @Override
        public String detect(String identifier) {
            return identifier.equals(name) ? name : null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class PrefixSuffixNaming extends Naming {
        private final String prefix;
        private final String suffix;
        private final int minLength;

        PrefixSuffixNaming(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.minLength = prefix.length() + suffix.length();
        }

        @Override
        public String apply(String input, As as) {
            String result;
            if (prefix.isEmpty()) {
                result = input + suffix;
            } else {
                String fixedInput = input.isEmpty() ? input : Character.toUpperCase(input.charAt(0)) + input.substring(1);
                result = prefix + fixedInput + suffix;
            }

            return switch (as) {
                case IDENTICAL -> result;
                case SCREMALIZED -> Strings.screamilize(result);
            };
        }

        @Override
        public String detect(String identifier) {
            if (identifier.length() <= minLength) {
                return null;
            }

            boolean prefixMatches = prefix.isEmpty() || identifier.startsWith(prefix) &&
                    Character.isUpperCase(identifier.charAt(prefix.length()));

            boolean suffixMatches = suffix.isEmpty() || identifier.endsWith(suffix);

            if (!prefixMatches || !suffixMatches) {
                return null;
            }

            String detected = identifier.substring(prefix.length(), identifier.length() - suffix.length());
            return prefix.isEmpty() ? detected : Character.toLowerCase(detected.charAt(0)) + detected.substring(1);
        }

        @Override
        public String toString() {
            return prefix + NAME_PLACEHOLDER + suffix;
        }
    }
}
