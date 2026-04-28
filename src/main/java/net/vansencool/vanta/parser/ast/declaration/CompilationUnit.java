package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents the top level compilation unit (a single .java file).
 *
 * @param packageName      the package name, or null if in default package
 * @param imports          the import declarations
 * @param typeDeclarations the top level type declarations
 * @param line             the source line number
 */
public record CompilationUnit(@Nullable String packageName, @NotNull List<ImportDeclaration> imports,
                              @NotNull List<AstNode> typeDeclarations, int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
