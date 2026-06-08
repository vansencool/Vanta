package net.vansencool.vanta.lower;

import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import org.jetbrains.annotations.NotNull;

/**
 * One AST to AST rewrite step run after parse and before resolve/codegen.
 */
public interface LowerPass {

    /**
     * @param unit input compilation unit
     * @return rewritten unit (may be {@code unit} unchanged)
     */
    @NotNull CompilationUnit lower(@NotNull CompilationUnit unit);
}
