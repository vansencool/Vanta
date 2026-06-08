package net.vansencool.vanta.lower.impl;

import net.vansencool.vanta.lower.AstRewriter;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites {@code try (R r = ...) { ... } catch (E e) { ... }} into a nested
 * form: a pure resource try wrapped in a plain try that owns the catch and
 * finally clauses. Matches the javac desugar shape.
 */
public final class TwrCatchSplitter extends AstRewriter {

    private static final @NotNull TwrCatchSplitter INSTANCE = new TwrCatchSplitter();

    private TwrCatchSplitter() {
    }

    public static @NotNull TwrCatchSplitter instance() {
        return INSTANCE;
    }

    @Override
    protected @NotNull Statement rewriteTry(@NotNull TryStatement ts) {
        Statement walked = super.rewriteTry(ts);
        if (!(walked instanceof TryStatement w)) return walked;
        if (w.resources().isEmpty()) return w;
        if (w.catchClauses().isEmpty() && w.finallyBlock() == null) return w;
        TryStatement pureTwr = new TryStatement(w.resources(), w.tryBlock(), List.of(), null, w.line());
        BlockStatement outerTry = new BlockStatement(List.of(pureTwr), w.line());
        return new TryStatement(List.of(), outerTry, new ArrayList<>(w.catchClauses()), w.finallyBlock(), w.line());
    }
}
