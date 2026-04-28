package net.vansencool.vanta.lexer;

import net.vansencool.vanta.lexer.exception.LexerException;
import net.vansencool.vanta.lexer.token.Token;
import net.vansencool.vanta.lexer.token.TokenType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer that tokenizes Java source code.
 */
public final class Lexer {

    private final char @NotNull [] source;
    private final int length;
    private int pos;
    private int line;
    private int column;

    /**
     * Creates a lexer for the given source code.
     *
     * @param source the Java source code to tokenize
     */
    public Lexer(@NotNull String source) {
        this.source = source.toCharArray();
        this.length = this.source.length;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    /**
     * Checks if a character is a valid hexadecimal digit.
     *
     * @param c the character to check
     * @return true if the character is a hex digit
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Tokenizes the entire source and returns all tokens, ending with EOF.
     *
     * @return an immutable list of tokens
     */
    public @NotNull List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>(Math.max(16, length / 5));
        while (pos < length) {
            skipWhitespaceAndComments();
            if (pos >= length) {
                break;
            }
            tokens.add(nextToken());
        }
        tokens.add(new Token(TokenType.EOF, "", line, column));
        return tokens;
    }

    /**
     * Reads the next token from the current position.
     *
     * @return the next token
     */
    private @NotNull Token nextToken() {
        char c = source[pos];
        int startLine = line;
        int startCol = column;

        if (c == '"') {
            if (pos + 2 < length && source[pos + 1] == '"' && source[pos + 2] == '"') {
                return readTextBlock(startLine, startCol);
            }
            return readString(startLine, startCol);
        }

        if (c == '\'') {
            return readChar(startLine, startCol);
        }

        if (Character.isDigit(c)) {
            return readNumber(startLine, startCol);
        }

        if (Character.isJavaIdentifierStart(c)) {
            return readIdentifierOrKeyword(startLine, startCol);
        }

        return readOperatorOrDelimiter(startLine, startCol);
    }

    /**
     * Skips whitespace characters and comments (single line, multi line).
     */
    private void skipWhitespaceAndComments() {
        while (pos < length) {
            char c = source[pos];
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
                line++;
                column = 1;
            } else if (c == '/' && pos + 1 < length && source[pos + 1] == '/') {
                skipLineComment();
            } else if (c == '/' && pos + 1 < length && source[pos + 1] == '*') {
                skipBlockComment();
            } else {
                break;
            }
        }
    }

    /**
     * Skips a single line comment starting with //.
     */
    private void skipLineComment() {
        pos += 2;
        column += 2;
        while (pos < length && source[pos] != '\n') {
            advance();
        }
    }

    /**
     * Skips a block comment or javadoc comment.
     */
    private void skipBlockComment() {
        int startLine = line;
        int startCol = column;
        pos += 2;
        column += 2;
        while (pos < length) {
            if (source[pos] == '*' && pos + 1 < length && source[pos + 1] == '/') {
                pos += 2;
                column += 2;
                return;
            }
            if (source[pos] == '\n') {
                pos++;
                line++;
                column = 1;
            } else {
                advance();
            }
        }
        throw new LexerException("Unterminated block comment", startLine, startCol);
    }

    /**
     * Reads a string literal enclosed in double quotes.
     *
     * @param startLine the line where the string starts
     * @param startCol  the column where the string starts
     * @return a STRING_LITERAL token
     */
    private @NotNull Token readString(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        advance();
        while (pos < length && source[pos] != '"') {
            if (source[pos] == '\\') {
                sb.append(source[pos]);
                advance();
                if (pos < length) {
                    sb.append(source[pos]);
                    advance();
                }
            } else if (source[pos] == '\n') {
                throw new LexerException("Unterminated string literal", startLine, startCol);
            } else {
                sb.append(source[pos]);
                advance();
            }
        }
        if (pos >= length) {
            throw new LexerException("Unterminated string literal", startLine, startCol);
        }
        sb.append('"');
        advance();
        return new Token(TokenType.STRING_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Reads a text block (triple quoted string).
     *
     * @param startLine the line where the text block starts
     * @param startCol  the column where the text block starts
     * @return a TEXT_BLOCK token
     */
    private @NotNull Token readTextBlock(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"\"\"");
        pos += 3;
        column += 3;
        while (pos < length) {
            if (source[pos] == '"' && pos + 2 < length && source[pos + 1] == '"' && source[pos + 2] == '"') {
                sb.append("\"\"\"");
                pos += 3;
                column += 3;
                return new Token(TokenType.TEXT_BLOCK, sb.toString(), startLine, startCol);
            }
            if (source[pos] == '\n') {
                sb.append('\n');
                pos++;
                line++;
                column = 1;
            } else {
                sb.append(source[pos]);
                advance();
            }
        }
        throw new LexerException("Unterminated text block", startLine, startCol);
    }

    /**
     * Reads a character literal enclosed in single quotes.
     *
     * @param startLine the line where the char starts
     * @param startCol  the column where the char starts
     * @return a CHAR_LITERAL token
     */
    private @NotNull Token readChar(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        sb.append('\'');
        advance();
        if (pos < length && source[pos] == '\\') {
            sb.append(source[pos]);
            advance();
            if (pos < length) {
                char esc = source[pos];
                sb.append(esc);
                advance();
                if (esc == 'u') {
                    for (int i = 0; i < 4 && pos < length && isHexDigit(source[pos]); i++) {
                        sb.append(source[pos]);
                        advance();
                    }
                } else if (esc >= '0' && esc <= '7') {
                    for (int i = 0; i < 2 && pos < length && source[pos] >= '0' && source[pos] <= '7'; i++) {
                        sb.append(source[pos]);
                        advance();
                    }
                }
            }
        } else if (pos < length && source[pos] != '\'') {
            sb.append(source[pos]);
            advance();
        }
        if (pos >= length || source[pos] != '\'') {
            throw new LexerException("Unterminated character literal", startLine, startCol);
        }
        sb.append('\'');
        advance();
        return new Token(TokenType.CHAR_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Builds a {@link TokenType#DOUBLE_LITERAL} token from the accumulated
     * digit/exponent buffer at the given source position.
     */
    private @NotNull Token doubleToken(@NotNull StringBuilder sb, int startLine, int startCol) {
        return new Token(TokenType.DOUBLE_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Reads a numeric literal (int, long, float, double, hex, binary, octal).
     *
     * @param startLine the line where the number starts
     * @param startCol  the column where the number starts
     * @return the appropriate numeric literal token
     */
    private @NotNull Token readNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        boolean isFloat = false;
        boolean isLong = false;

        if (source[pos] == '0' && pos + 1 < length) {
            char next = source[pos + 1];
            if (next == 'x' || next == 'X') {
                return readHexNumber(startLine, startCol);
            }
            if (next == 'b' || next == 'B') {
                return readBinaryNumber(startLine, startCol);
            }
        }

        while (pos < length) {
            char c = source[pos];
            if (Character.isDigit(c) || c == '_') {
                sb.append(c);
                advance();
            } else if (c == '.' && !isFloat) {
                if (pos + 1 < length && Character.isDigit(source[pos + 1])) {
                    isFloat = true;
                    sb.append(c);
                    advance();
                } else if (pos + 1 < length && source[pos + 1] == '.') {
                    break;
                } else {
                    isFloat = true;
                    sb.append(c);
                    advance();
                }
            } else if ((c == 'e' || c == 'E') && !isLong) {
                isFloat = true;
                sb.append(c);
                advance();
                if (pos < length && (source[pos] == '+' || source[pos] == '-')) {
                    sb.append(source[pos]);
                    advance();
                }
            } else if (c == 'f' || c == 'F') {
                sb.append(c);
                advance();
                return new Token(TokenType.FLOAT_LITERAL, sb.toString(), startLine, startCol);
            } else if (c == 'd' || c == 'D') {
                sb.append(c);
                advance();
                return doubleToken(sb, startLine, startCol);
            } else if (c == 'l' || c == 'L') {
                sb.append(c);
                advance();
                return new Token(TokenType.LONG_LITERAL, sb.toString(), startLine, startCol);
            } else {
                break;
            }
        }

        if (isFloat) {
            return doubleToken(sb, startLine, startCol);
        }
        return new Token(TokenType.INT_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Reads a hexadecimal number literal.
     *
     * @param startLine the line where the number starts
     * @param startCol  the column where the number starts
     * @return the hex literal token
     */
    private @NotNull Token readHexNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        sb.append(source[pos]);
        advance();
        sb.append(source[pos]);
        advance();
        boolean isFloat = false;
        boolean sawExponent = false;
        while (pos < length) {
            char c = source[pos];
            if (isHexDigit(c) || c == '_') {
                sb.append(c);
                advance();
            } else if (c == '.' && !isFloat) {
                isFloat = true;
                sb.append(c);
                advance();
            } else if ((c == 'p' || c == 'P') && !sawExponent) {
                isFloat = true;
                sawExponent = true;
                sb.append(c);
                advance();
                if (pos < length && (source[pos] == '+' || source[pos] == '-')) {
                    sb.append(source[pos]);
                    advance();
                }
            } else if (c == 'l' || c == 'L') {
                if (isFloat) break;
                sb.append(c);
                advance();
                return new Token(TokenType.LONG_LITERAL, sb.toString(), startLine, startCol);
            } else if (c == 'f' || c == 'F') {
                if (!isFloat) break;
                sb.append(c);
                advance();
                return new Token(TokenType.FLOAT_LITERAL, sb.toString(), startLine, startCol);
            } else if (c == 'd' || c == 'D') {
                if (!isFloat) break;
                sb.append(c);
                advance();
                return doubleToken(sb, startLine, startCol);
            } else {
                break;
            }
        }
        if (isFloat) return doubleToken(sb, startLine, startCol);
        return new Token(TokenType.INT_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Reads a binary number literal (0b or 0B prefix).
     *
     * @param startLine the line where the number starts
     * @param startCol  the column where the number starts
     * @return the binary literal token
     */
    private @NotNull Token readBinaryNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        sb.append(source[pos]);
        advance();
        sb.append(source[pos]);
        advance();
        while (pos < length) {
            char c = source[pos];
            if (c == '0' || c == '1' || c == '_') {
                sb.append(c);
                advance();
            } else if (c == 'l' || c == 'L') {
                sb.append(c);
                advance();
                return new Token(TokenType.LONG_LITERAL, sb.toString(), startLine, startCol);
            } else {
                break;
            }
        }
        return new Token(TokenType.INT_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Reads an identifier or keyword token.
     *
     * @param startLine the line where the identifier starts
     * @param startCol  the column where the identifier starts
     * @return an IDENTIFIER or keyword token
     */
    private @NotNull Token readIdentifierOrKeyword(int startLine, int startCol) {
        int start = pos;
        while (pos < length && Character.isJavaIdentifierPart(source[pos])) {
            advance();
        }
        Keywords.Entry kw = Keywords.match(source, start, pos);
        if (kw != null) return new Token(Keywords.type(kw), Keywords.word(kw), startLine, startCol);
        String word = new String(source, start, pos - start);
        return new Token(TokenType.IDENTIFIER, word, startLine, startCol);
    }

    /**
     * Reads an operator or delimiter token.
     *
     * @param startLine the starting line
     * @param startCol  the starting column
     * @return the operator or delimiter token
     */
    private @NotNull Token readOperatorOrDelimiter(int startLine, int startCol) {
        char c = source[pos];
        advance();
        switch (c) {
            case '(':
                return new Token(TokenType.LEFT_PAREN, "(", startLine, startCol);
            case ')':
                return new Token(TokenType.RIGHT_PAREN, ")", startLine, startCol);
            case '{':
                return new Token(TokenType.LEFT_BRACE, "{", startLine, startCol);
            case '}':
                return new Token(TokenType.RIGHT_BRACE, "}", startLine, startCol);
            case '[':
                return new Token(TokenType.LEFT_BRACKET, "[", startLine, startCol);
            case ']':
                return new Token(TokenType.RIGHT_BRACKET, "]", startLine, startCol);
            case ';':
                return new Token(TokenType.SEMICOLON, ";", startLine, startCol);
            case ',':
                return new Token(TokenType.COMMA, ",", startLine, startCol);
            case '@':
                return new Token(TokenType.AT, "@", startLine, startCol);
            case '~':
                return new Token(TokenType.TILDE, "~", startLine, startCol);
            case '?':
                return new Token(TokenType.QUESTION, "?", startLine, startCol);
            case ':':
                if (pos < length && source[pos] == ':') {
                    advance();
                    return new Token(TokenType.DOUBLE_COLON, "::", startLine, startCol);
                }
                return new Token(TokenType.COLON, ":", startLine, startCol);
            case '.':
                if (pos + 1 < length && source[pos] == '.' && source[pos + 1] == '.') {
                    advance();
                    advance();
                    return new Token(TokenType.ELLIPSIS, "...", startLine, startCol);
                }
                if (pos < length && Character.isDigit(source[pos])) {
                    return readNumberAfterDot(startLine, startCol);
                }
                return new Token(TokenType.DOT, ".", startLine, startCol);
            case '+':
                if (pos < length && source[pos] == '+') {
                    advance();
                    return new Token(TokenType.INCREMENT, "++", startLine, startCol);
                }
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.PLUS_ASSIGN, "+=", startLine, startCol);
                }
                return new Token(TokenType.PLUS, "+", startLine, startCol);
            case '-':
                if (pos < length && source[pos] == '-') {
                    advance();
                    return new Token(TokenType.DECREMENT, "--", startLine, startCol);
                }
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.MINUS_ASSIGN, "-=", startLine, startCol);
                }
                if (pos < length && source[pos] == '>') {
                    advance();
                    return new Token(TokenType.ARROW, "->", startLine, startCol);
                }
                return new Token(TokenType.MINUS, "-", startLine, startCol);
            case '*':
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.STAR_ASSIGN, "*=", startLine, startCol);
                }
                return new Token(TokenType.STAR, "*", startLine, startCol);
            case '/':
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.SLASH_ASSIGN, "/=", startLine, startCol);
                }
                return new Token(TokenType.SLASH, "/", startLine, startCol);
            case '%':
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.PERCENT_ASSIGN, "%=", startLine, startCol);
                }
                return new Token(TokenType.PERCENT, "%", startLine, startCol);
            case '&':
                if (pos < length && source[pos] == '&') {
                    advance();
                    return new Token(TokenType.AND, "&&", startLine, startCol);
                }
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.AMPERSAND_ASSIGN, "&=", startLine, startCol);
                }
                return new Token(TokenType.AMPERSAND, "&", startLine, startCol);
            case '|':
                if (pos < length && source[pos] == '|') {
                    advance();
                    return new Token(TokenType.OR, "||", startLine, startCol);
                }
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.PIPE_ASSIGN, "|=", startLine, startCol);
                }
                return new Token(TokenType.PIPE, "|", startLine, startCol);
            case '^':
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.CARET_ASSIGN, "^=", startLine, startCol);
                }
                return new Token(TokenType.CARET, "^", startLine, startCol);
            case '!':
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.NOT_EQUAL, "!=", startLine, startCol);
                }
                return new Token(TokenType.EXCLAMATION, "!", startLine, startCol);
            case '=':
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.EQUAL, "==", startLine, startCol);
                }
                return new Token(TokenType.ASSIGN, "=", startLine, startCol);
            case '<':
                if (pos < length && source[pos] == '<') {
                    advance();
                    if (pos < length && source[pos] == '=') {
                        advance();
                        return new Token(TokenType.LEFT_SHIFT_ASSIGN, "<<=", startLine, startCol);
                    }
                    return new Token(TokenType.LEFT_SHIFT, "<<", startLine, startCol);
                }
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.LESS_EQUAL, "<=", startLine, startCol);
                }
                return new Token(TokenType.LESS, "<", startLine, startCol);
            case '>':
                if (pos < length && source[pos] == '>') {
                    advance();
                    if (pos < length && source[pos] == '>') {
                        advance();
                        if (pos < length && source[pos] == '=') {
                            advance();
                            return new Token(TokenType.UNSIGNED_RIGHT_SHIFT_ASSIGN, ">>>=", startLine, startCol);
                        }
                        return new Token(TokenType.UNSIGNED_RIGHT_SHIFT, ">>>", startLine, startCol);
                    }
                    if (pos < length && source[pos] == '=') {
                        advance();
                        return new Token(TokenType.RIGHT_SHIFT_ASSIGN, ">>=", startLine, startCol);
                    }
                    return new Token(TokenType.RIGHT_SHIFT, ">>", startLine, startCol);
                }
                if (pos < length && source[pos] == '=') {
                    advance();
                    return new Token(TokenType.GREATER_EQUAL, ">=", startLine, startCol);
                }
                return new Token(TokenType.GREATER, ">", startLine, startCol);
            default:
                throw new LexerException("Unexpected character: '" + c + "'", startLine, startCol);
        }
    }

    /**
     * Reads a floating point number that started with a dot (e.g., .5).
     *
     * @param startLine the starting line
     * @param startCol  the starting column
     * @return a DOUBLE_LITERAL or FLOAT_LITERAL token
     */
    private @NotNull Token readNumberAfterDot(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder(".");
        while (pos < length && (Character.isDigit(source[pos]) || source[pos] == '_')) {
            sb.append(source[pos]);
            advance();
        }
        if (pos < length && (source[pos] == 'e' || source[pos] == 'E')) {
            sb.append(source[pos]);
            advance();
            if (pos < length && (source[pos] == '+' || source[pos] == '-')) {
                sb.append(source[pos]);
                advance();
            }
            while (pos < length && Character.isDigit(source[pos])) {
                sb.append(source[pos]);
                advance();
            }
        }
        if (pos < length && (source[pos] == 'f' || source[pos] == 'F')) {
            sb.append(source[pos]);
            advance();
            return new Token(TokenType.FLOAT_LITERAL, sb.toString(), startLine, startCol);
        }
        if (pos < length && (source[pos] == 'd' || source[pos] == 'D')) {
            sb.append(source[pos]);
            advance();
        }
        return new Token(TokenType.DOUBLE_LITERAL, sb.toString(), startLine, startCol);
    }

    /**
     * Advances the position by one character.
     */
    private void advance() {
        pos++;
        column++;
    }
}
