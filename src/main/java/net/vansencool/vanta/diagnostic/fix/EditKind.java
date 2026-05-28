package net.vansencool.vanta.diagnostic.fix;

/**
 * Classifies a single source edit so the renderer can pick an appropriate
 * underline glyph and label.
 */
public enum EditKind {
    INSERT,
    REPLACE,
    DELETE,
    INSERT_LINE_AFTER,
    INSERT_LINE_BEFORE
}
