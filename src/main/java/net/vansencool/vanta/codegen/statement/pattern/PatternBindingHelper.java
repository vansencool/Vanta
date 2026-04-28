package net.vansencool.vanta.codegen.statement.pattern;

import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks an {@code if} condition to find pattern matching binding variables
 * and to detect when those bindings should outlive the if body. Java flow
 * scoping can promote a pattern variable to the enclosing block when the
 * then branch exits abruptly, and this helper computes the slot watermark
 * the surrounding statement generator must preserve.
 */
public final class PatternBindingHelper {

    private final @NotNull MethodContext ctx;

    /**
     * @param ctx owning method context
     */
    public PatternBindingHelper(@NotNull MethodContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @param cond condition expression
     * @return the highest local slot occupied by any pattern binding variable
     * declared in {@code cond}
     */
    public int patternVarSlotEnd(@NotNull Expression cond) {
        int max = 0;
        for (String name : collectPatternVarNames(cond)) {
            LocalVariable lv = ctx.scope().resolve(name);
            if (lv != null) {
                int end = lv.index() + lv.type().stackSize();
                if (end > max) max = end;
            }
        }
        return max;
    }

    /**
     * @param cond condition expression
     * @return names of all pattern binding variables declared in {@code cond}
     */
    public @NotNull List<String> collectPatternVarNames(@NotNull Expression cond) {
        List<String> out = new ArrayList<>();
        collectPatternVarNamesInto(cond, out);
        return out;
    }

    /**
     * @param cond condition expression
     * @param out  accumulator
     */
    private void collectPatternVarNamesInto(@NotNull Expression cond, @NotNull List<String> out) {
        Expression cur = cond;
        while (cur instanceof ParenExpression p) cur = p.expression();
        if (cur instanceof InstanceofExpression io && io.patternVariable() != null) {
            out.add(io.patternVariable());
        }
        if (cur instanceof BinaryExpression b && ("||".equals(b.operator()) || "&&".equals(b.operator()))) {
            collectPatternVarNamesInto(b.left(), out);
            collectPatternVarNamesInto(b.right(), out);
        }
        if (cur instanceof UnaryExpression u && "!".equals(u.operator()) && u.isPrefix()) {
            collectPatternVarNamesInto(u.operand(), out);
        }
    }

    /**
     * @param cond condition expression
     * @return true when the condition introduces a pattern binding that flow
     * scoping promotes to the enclosing block on the fall through
     * path, so the binding must outlive the if statement
     */
    public boolean hasNegatedPatternBinding(@NotNull Expression cond) {
        Expression cur = cond;
        while (cur instanceof ParenExpression p) cur = p.expression();
        if (cur instanceof UnaryExpression u && "!".equals(u.operator()) && u.isPrefix()) {
            return containsInstanceofWithBinding(u.operand());
        }
        if (cur instanceof BinaryExpression b && ("||".equals(b.operator()) || "&&".equals(b.operator()))) {
            return hasNegatedPatternBinding(b.left()) || hasNegatedPatternBinding(b.right());
        }
        return false;
    }

    /**
     * @param expr boolean expression
     * @return true when {@code expr} contains an {@code x instanceof T v}
     * sub expression declaring a pattern variable
     */
    private boolean containsInstanceofWithBinding(@NotNull Expression expr) {
        Expression cur = expr;
        while (cur instanceof ParenExpression p) cur = p.expression();
        if (cur instanceof InstanceofExpression io) return io.patternVariable() != null;
        if (cur instanceof BinaryExpression b && ("||".equals(b.operator()) || "&&".equals(b.operator()))) {
            return containsInstanceofWithBinding(b.left()) || containsInstanceofWithBinding(b.right());
        }
        if (cur instanceof UnaryExpression u && "!".equals(u.operator()) && u.isPrefix()) {
            return containsInstanceofWithBinding(u.operand());
        }
        return false;
    }
}
