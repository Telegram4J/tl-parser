package telegram4j.tl.generator;

public final class Strings {

    private Strings() {}

    static String camelize(String str) {
        return camelize(str, false);
    }

    static String camelize(String str, boolean firstAsLower) {
        StringBuilder builder = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (i == 0 && firstAsLower) {
                char n = Character.toLowerCase(str.charAt(i));
                builder.append(n);
            } else if ((c == '_' || c == '.') && ++i < str.length()) {
                char n = Character.toUpperCase(str.charAt(i));
                builder.append(n);
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    static String screamilize(CharSequence cs) {
        StringBuilder buf = new StringBuilder(cs.length());
        for (int i = 0; i < cs.length(); i++) {
            char p = i - 1 != -1 ? cs.charAt(i - 1) : Character.MIN_VALUE;
            char c = cs.charAt(i);

            if (Character.isLetter(c) && Character.isLetter(p) &&
                Character.isLowerCase(p) && Character.isUpperCase(c)) {
                buf.append('_');
            }

            if (c == '.' || c == '-' || c == '_' || Character.isWhitespace(c) &&
                    Character.isLetterOrDigit(p) && i + 1 < cs.length() &&
                    Character.isLetterOrDigit(cs.charAt(i + 1))) {

                buf.append('_');
            } else {
                buf.append(Character.toUpperCase(c));
            }
        }
        return buf.toString();
    }

    static String findCommonPart(String s1, String s2) {
        if (s1.equals(s2)) {
            return s1;
        }
        for (int i = 0, n = Math.min(s1.length(), s2.length()); i < n; i++) {
            if (s1.charAt(i) != s2.charAt(i)) {
                return s1.substring(0, i);
            }
        }
        return s1;
    }
}
