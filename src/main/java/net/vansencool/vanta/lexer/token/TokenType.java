package net.vansencool.vanta.lexer.token;

/**
 * Enumerates every token type produced by the Vanta lexer.
 * Organized by category: keywords, literals, operators, delimiters, and special tokens.
 */
public enum TokenType {

    // ---- Keywords ----
    ABSTRACT,
    ASSERT,
    BOOLEAN,
    BREAK,
    BYTE,
    CASE,
    CATCH,
    CHAR,
    CLASS,
    CONST,
    CONTINUE,
    DEFAULT,
    DO,
    DOUBLE,
    ELSE,
    ENUM,
    EXTENDS,
    FINAL,
    FINALLY,
    FLOAT,
    FOR,
    GOTO,
    IF,
    IMPLEMENTS,
    IMPORT,
    INSTANCEOF,
    INT,
    INTERFACE,
    LONG,
    NATIVE,
    NEW,
    PACKAGE,
    PRIVATE,
    PROTECTED,
    PUBLIC,
    RECORD,
    RETURN,
    SHORT,
    STATIC,
    STRICTFP,
    SUPER,
    SWITCH,
    SYNCHRONIZED,
    THIS,
    THROW,
    THROWS,
    TRANSIENT,
    TRY,
    VAR,
    VOID,
    VOLATILE,
    WHILE,
    YIELD,

    // ---- Boolean and null literals ----
    TRUE,
    FALSE,
    NULL,

    // ---- Literals ----
    INT_LITERAL,
    LONG_LITERAL,
    FLOAT_LITERAL,
    DOUBLE_LITERAL,
    CHAR_LITERAL,
    STRING_LITERAL,
    TEXT_BLOCK,

    // ---- Identifier ----
    IDENTIFIER,

    // ---- Operators ----
    PLUS,              // +
    MINUS,             // -
    STAR,              // *
    SLASH,             // /
    PERCENT,           // %
    AMPERSAND,         // &
    PIPE,              // |
    CARET,             // ^
    TILDE,             // ~
    EXCLAMATION,       // !
    ASSIGN,            // =
    LESS,              // <
    GREATER,           // >
    QUESTION,          // ?
    COLON,             // :
    ARROW,             // ->
    DOUBLE_COLON,      // ::
    INCREMENT,         // ++
    DECREMENT,         // --
    AND,               // &&
    OR,                // ||
    EQUAL,             // ==
    NOT_EQUAL,         // !=
    LESS_EQUAL,        // <=
    GREATER_EQUAL,     // >=
    LEFT_SHIFT,        // <<
    RIGHT_SHIFT,       // >>
    UNSIGNED_RIGHT_SHIFT, // >>>
    PLUS_ASSIGN,       // +=
    MINUS_ASSIGN,      // -=
    STAR_ASSIGN,       // *=
    SLASH_ASSIGN,      // /=
    PERCENT_ASSIGN,    // %=
    AMPERSAND_ASSIGN,  // &=
    PIPE_ASSIGN,       // |=
    CARET_ASSIGN,      // ^=
    LEFT_SHIFT_ASSIGN, // <<=
    RIGHT_SHIFT_ASSIGN, // >>=
    UNSIGNED_RIGHT_SHIFT_ASSIGN, // >>>=

    // ---- Delimiters ----
    LEFT_PAREN,        // (
    RIGHT_PAREN,       // )
    LEFT_BRACE,        // {
    RIGHT_BRACE,       // }
    LEFT_BRACKET,      // [
    RIGHT_BRACKET,     // ]
    SEMICOLON,         // ;
    COMMA,             // ,
    DOT,               // .
    ELLIPSIS,          // ...
    AT,                // @

    // ---- Special ----
    EOF;

    /**
     * @return true if this token type represents a primitive type keyword
     */
    public boolean isPrimitive() {
        return this == BOOLEAN || this == BYTE || this == SHORT || this == INT || this == LONG || this == FLOAT || this == DOUBLE || this == CHAR;
    }

    /**
     * @return true if this token type represents an assignment operator
     */
    public boolean isAssignment() {
        return this == ASSIGN || this == PLUS_ASSIGN || this == MINUS_ASSIGN || this == STAR_ASSIGN || this == SLASH_ASSIGN || this == PERCENT_ASSIGN || this == AMPERSAND_ASSIGN || this == PIPE_ASSIGN || this == CARET_ASSIGN || this == LEFT_SHIFT_ASSIGN || this == RIGHT_SHIFT_ASSIGN || this == UNSIGNED_RIGHT_SHIFT_ASSIGN;
    }
}
