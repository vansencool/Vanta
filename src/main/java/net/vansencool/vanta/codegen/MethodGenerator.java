package net.vansencool.vanta.codegen;

import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.flow.UnreachableCodeDiagnostic;
import net.vansencool.vanta.exception.CompilationException;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import org.jetbrains.annotations.NotNull;

/**
 * Orchestrates method body bytecode generation by delegating
 * to StatementGenerator for statements and ExpressionGenerator for expressions.
 */
public final class MethodGenerator {

    private final @NotNull MethodContext ctx;
    private final @NotNull StatementGenerator statementGenerator;

    /**
     * Creates a method generator.
     *
     * @param ctx the method context
     */
    public MethodGenerator(@NotNull MethodContext ctx) {
        this.ctx = ctx;
        this.statementGenerator = new StatementGenerator(ctx, new ExpressionGenerator(ctx));
    }

    /**
     * Generates bytecode for a method body block.
     *
     * @param body the block statement
     */
    public void generateBody(@NotNull BlockStatement body) {
        Statement terminator = null;
        for (Statement stmt : body.statements()) {
            if (!ctx.isReachable() && terminator != null) {
                throw new CompilationException(UnreachableCodeDiagnostic.build(ctx, stmt, terminator));
            }
            statementGenerator.generate(stmt);
            if (!ctx.isReachable() && terminator == null) terminator = stmt;
        }
    }

    public void generate(@NotNull Statement stmt) {
        statementGenerator.generate(stmt);
    }
}
