package net.vansencool.vanta.codegen;

import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import org.jetbrains.annotations.NotNull;

/**
 * Orchestrates method body bytecode generation by delegating
 * to StatementGenerator for statements and ExpressionGenerator for expressions.
 */
public final class MethodGenerator {

    private final @NotNull StatementGenerator statementGenerator;

    /**
     * Creates a method generator.
     *
     * @param ctx the method context
     */
    public MethodGenerator(@NotNull MethodContext ctx) {
        this.statementGenerator = new StatementGenerator(ctx, new ExpressionGenerator(ctx));
    }

    /**
     * Generates bytecode for a method body block.
     *
     * @param body the block statement
     */
    public void generateBody(@NotNull BlockStatement body) {
        for (Statement stmt : body.statements()) {
            statementGenerator.generate(stmt);
        }
    }

    public void generate(@NotNull Statement stmt) {
        statementGenerator.generate(stmt);
    }
}
