package net.vansencool.vanta.parser.ast;

import org.jetbrains.annotations.NotNull;

/**
 * Base interface for all AST nodes.
 * Every node carries line information for debug output.
 */
public interface AstNode {

    /**
     * @return the 1 based line number where this node starts in source
     */
    int line();

    /**
     * Accepts a visitor for tree traversal.
     *
     * @param visitor the visitor to accept
     */
    void accept(@NotNull AstVisitor visitor);
}
