package net.vansencool.vanta.codegen.classes.literal;

import org.jetbrains.annotations.NotNull;

/**
 * Parses Java source-level numeric, character, and string literal tokens into
 * their host values. Handles the full Java 17 lexical grammar for each literal
 * kind (underscore separators, binary/octal/hex prefixes, escape sequences,
 * text blocks, unicode escapes) so code generators can fold constants without
 * re-implementing the rules.
 */
public final class LiteralParser {

    private LiteralParser() {
    }

    /**
     * Parses an integer literal source token, accepting the full Java 17 grammar
     * (hex {@code 0x}/{@code 0X}, binary {@code 0b}/{@code 0B}, leading-zero
     * octal, and {@code _} separators anywhere in the digits).
     *
     * @param value raw literal text as it appeared in source
     * @return parsed {@code int} value
     */
    public static int parseIntLiteral(@NotNull String value) {
        String cleaned = value.replace("_", "");
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X"))
            return Integer.parseUnsignedInt(cleaned.substring(2), 16);
        if (cleaned.startsWith("0b") || cleaned.startsWith("0B"))
            return Integer.parseUnsignedInt(cleaned.substring(2), 2);
        if (cleaned.startsWith("0") && cleaned.length() > 1 && !cleaned.contains("."))
            return Integer.parseUnsignedInt(cleaned.substring(1), 8);
        return Integer.parseInt(cleaned);
    }

    /**
     * Long-valued counterpart to {@link #parseIntLiteral(String)}. Also strips
     * the trailing {@code L}/{@code l} suffix because the caller passes the
     * raw token.
     *
     * @param value raw literal text as it appeared in source
     * @return parsed {@code long} value
     */
    public static long parseLongLiteral(@NotNull String value) {
        String cleaned = value.replace("_", "").replace("L", "").replace("l", "");
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X"))
            return Long.parseUnsignedLong(cleaned.substring(2), 16);
        if (cleaned.startsWith("0b") || cleaned.startsWith("0B"))
            return Long.parseUnsignedLong(cleaned.substring(2), 2);
        if (cleaned.startsWith("0") && cleaned.length() > 1) return Long.parseUnsignedLong(cleaned.substring(1), 8);
        return Long.parseLong(cleaned);
    }

    /**
     * Decodes a character literal source token (quoted single char, standard
     * escape, octal escape, or {@code \\u} unicode escape) into the char it
     * denotes.
     *
     * @param value raw literal text, optionally including surrounding {@code '} quotes
     * @return decoded character
     */
    public static char parseCharLiteral(@NotNull String value) {
        String inner = value;
        if (inner.startsWith("'") && inner.endsWith("'")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.length() == 1) return inner.charAt(0);
        if (inner.startsWith("\\")) {
            char c1 = inner.charAt(1);
            if (c1 >= '0' && c1 <= '7') {
                int end = 2;
                while (end < inner.length() && end < 4 && inner.charAt(end) >= '0' && inner.charAt(end) <= '7') end++;
                return (char) Integer.parseInt(inner.substring(1, end), 8);
            }
            return switch (c1) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '\\' -> '\\';
                case '\'' -> '\'';
                case '"' -> '"';
                case 'u' -> (char) Integer.parseInt(inner.substring(2), 16);
                default -> c1;
            };
        }
        return inner.charAt(0);
    }

    /**
     * Strips surrounding {@code "} or {@code """} quotes from a string literal
     * and decodes Java escape sequences in the contents.
     *
     * @param value raw literal token including quotes
     * @return string contents after unescaping
     */
    public static @NotNull String stripStringQuotes(@NotNull String value) {
        String inner;
        if (value.startsWith("\"\"\"") && value.endsWith("\"\"\"")) {
            inner = value.substring(3, value.length() - 3);
        } else if (value.startsWith("\"") && value.endsWith("\"")) {
            inner = value.substring(1, value.length() - 1);
        } else {
            return value;
        }
        return unescapeJavaString(inner);
    }

    /**
     * Decodes Java string escape sequences inside already-unquoted content. Handles
     * {@code \\n}/{@code \\t}/{@code \\r}/{@code \\b}/{@code \\f}/{@code \\s}/
     * {@code \\\\}/{@code \\'}/{@code \\"}, three-digit octal escapes, and
     * {@code \\uXXXX} unicode escapes.
     *
     * @param s raw string content (no outer quotes) possibly containing escape sequences
     * @return decoded host-level string
     */
    public static @NotNull String unescapeJavaString(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n >= '0' && n <= '7') {
                    int end = i;
                    while (end < s.length() && end - i < 3 && s.charAt(end) >= '0' && s.charAt(end) <= '7') end++;
                    sb.append((char) Integer.parseInt(s.substring(i, end), 8));
                    i = end - 1;
                } else switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 's' -> sb.append(' ');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
