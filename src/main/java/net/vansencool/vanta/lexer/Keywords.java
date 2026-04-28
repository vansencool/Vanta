package net.vansencool.vanta.lexer;

import net.vansencool.vanta.lexer.token.TokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps keyword strings to their corresponding token types.
 */
public final class Keywords {

    private static final @NotNull Map<String, TokenType> KEYWORD_MAP = new HashMap<>();
    private static final @NotNull Entry @Nullable [] @NotNull [] BY_LENGTH;

    static {
        KEYWORD_MAP.put("abstract", TokenType.ABSTRACT);
        KEYWORD_MAP.put("assert", TokenType.ASSERT);
        KEYWORD_MAP.put("boolean", TokenType.BOOLEAN);
        KEYWORD_MAP.put("break", TokenType.BREAK);
        KEYWORD_MAP.put("byte", TokenType.BYTE);
        KEYWORD_MAP.put("case", TokenType.CASE);
        KEYWORD_MAP.put("catch", TokenType.CATCH);
        KEYWORD_MAP.put("char", TokenType.CHAR);
        KEYWORD_MAP.put("class", TokenType.CLASS);
        KEYWORD_MAP.put("const", TokenType.CONST);
        KEYWORD_MAP.put("continue", TokenType.CONTINUE);
        KEYWORD_MAP.put("default", TokenType.DEFAULT);
        KEYWORD_MAP.put("do", TokenType.DO);
        KEYWORD_MAP.put("double", TokenType.DOUBLE);
        KEYWORD_MAP.put("else", TokenType.ELSE);
        KEYWORD_MAP.put("enum", TokenType.ENUM);
        KEYWORD_MAP.put("extends", TokenType.EXTENDS);
        KEYWORD_MAP.put("final", TokenType.FINAL);
        KEYWORD_MAP.put("finally", TokenType.FINALLY);
        KEYWORD_MAP.put("float", TokenType.FLOAT);
        KEYWORD_MAP.put("for", TokenType.FOR);
        KEYWORD_MAP.put("goto", TokenType.GOTO);
        KEYWORD_MAP.put("if", TokenType.IF);
        KEYWORD_MAP.put("implements", TokenType.IMPLEMENTS);
        KEYWORD_MAP.put("import", TokenType.IMPORT);
        KEYWORD_MAP.put("instanceof", TokenType.INSTANCEOF);
        KEYWORD_MAP.put("int", TokenType.INT);
        KEYWORD_MAP.put("interface", TokenType.INTERFACE);
        KEYWORD_MAP.put("long", TokenType.LONG);
        KEYWORD_MAP.put("native", TokenType.NATIVE);
        KEYWORD_MAP.put("new", TokenType.NEW);
        KEYWORD_MAP.put("package", TokenType.PACKAGE);
        KEYWORD_MAP.put("private", TokenType.PRIVATE);
        KEYWORD_MAP.put("protected", TokenType.PROTECTED);
        KEYWORD_MAP.put("public", TokenType.PUBLIC);
        KEYWORD_MAP.put("record", TokenType.RECORD);
        KEYWORD_MAP.put("return", TokenType.RETURN);
        KEYWORD_MAP.put("short", TokenType.SHORT);
        KEYWORD_MAP.put("static", TokenType.STATIC);
        KEYWORD_MAP.put("strictfp", TokenType.STRICTFP);
        KEYWORD_MAP.put("super", TokenType.SUPER);
        KEYWORD_MAP.put("switch", TokenType.SWITCH);
        KEYWORD_MAP.put("synchronized", TokenType.SYNCHRONIZED);
        KEYWORD_MAP.put("this", TokenType.THIS);
        KEYWORD_MAP.put("throw", TokenType.THROW);
        KEYWORD_MAP.put("throws", TokenType.THROWS);
        KEYWORD_MAP.put("transient", TokenType.TRANSIENT);
        KEYWORD_MAP.put("try", TokenType.TRY);
        KEYWORD_MAP.put("var", TokenType.VAR);
        KEYWORD_MAP.put("void", TokenType.VOID);
        KEYWORD_MAP.put("volatile", TokenType.VOLATILE);
        KEYWORD_MAP.put("while", TokenType.WHILE);
        KEYWORD_MAP.put("yield", TokenType.YIELD);
        KEYWORD_MAP.put("true", TokenType.TRUE);
        KEYWORD_MAP.put("false", TokenType.FALSE);
        KEYWORD_MAP.put("null", TokenType.NULL);
        int maxLen = 0;
        for (String k : KEYWORD_MAP.keySet()) if (k.length() > maxLen) maxLen = k.length();
        Entry[][] byLen = new Entry[maxLen + 1][];
        int[] counts = new int[maxLen + 1];
        for (String k : KEYWORD_MAP.keySet()) counts[k.length()]++;
        for (int i = 0; i <= maxLen; i++) if (counts[i] > 0) byLen[i] = new Entry[counts[i]];
        int[] idx = new int[maxLen + 1];
        for (Map.Entry<String, TokenType> e : KEYWORD_MAP.entrySet()) {
            int len = e.getKey().length();
            byLen[len][idx[len]++] = new Entry(e.getKey(), e.getValue());
        }
        BY_LENGTH = byLen;
    }

    private Keywords() {
    }

    /**
     * Resolves a word to its token type: keyword token if recognized, IDENTIFIER otherwise.
     *
     * @param word the word to resolve
     * @return the matching keyword token type, or IDENTIFIER
     */
    public static @NotNull TokenType resolve(@NotNull String word) {
        TokenType type = KEYWORD_MAP.get(word);
        return type != null ? type : TokenType.IDENTIFIER;
    }

    /**
     * Probes a keyword match directly against a raw {@code char[]} slice so the
     * caller can skip the {@link String} allocation when the slice turns out to
     * be a keyword. Returns the interned keyword entry, or {@code null} when the
     * slice is a user identifier and the caller must allocate a fresh string.
     */
    public static @Nullable Entry match(char @NotNull [] src, int start, int end) {
        int len = end - start;
        if (len <= 0 || len >= BY_LENGTH.length) return null;
        Entry[] bucket = BY_LENGTH[len];
        if (bucket == null) return null;
        outer:
        for (Entry e : bucket) {
            char[] ec = e.chars;
            for (int i = 0; i < len; i++) {
                if (src[start + i] != ec[i]) continue outer;
            }
            return e;
        }
        return null;
    }

    /**
     * @return the keyword entry's canonical word, avoiding the lexer's per-call allocation
     */
    public static @NotNull String word(@NotNull Entry entry) {
        return entry.word;
    }

    /**
     * @return the keyword entry's token type
     */
    public static @NotNull TokenType type(@NotNull Entry entry) {
        return entry.type;
    }

    public static final class Entry {
        private final char @NotNull [] chars;
        private final @NotNull String word;
        private final @NotNull TokenType type;

        private Entry(@NotNull String word, @NotNull TokenType type) {
            this.chars = word.toCharArray();
            this.word = word;
            this.type = type;
        }
    }
}
