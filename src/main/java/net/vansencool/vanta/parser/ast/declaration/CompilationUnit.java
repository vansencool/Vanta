package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.comment.CommentTable;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Top level compilation unit (a single source file).
 *
 * @param packageName      the package name, or null if in default package
 * @param imports          the import declarations
 * @param typeDeclarations the top level type declarations
 * @param commentTable     source comments attached to AST nodes
 * @param spanTable        source spans attached to AST nodes
 * @param line             the source line number
 */
public record CompilationUnit(@Nullable String packageName, @NotNull List<ImportDeclaration> imports,
                              @NotNull List<AstNode> typeDeclarations, @NotNull CommentTable commentTable,
                              @NotNull SpanTable spanTable, int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
