package net.vansencool.vanta.lower;

import net.vansencool.vanta.lower.impl.TwrCatchSplitter;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Ordered chain of {@link LowerPass}es applied to every parsed compilation unit.
 */
public final class LowerPipeline {

    private final @NotNull List<LowerPass> passes;

    public LowerPipeline(@NotNull List<LowerPass> passes) {
        this.passes = List.copyOf(passes);
    }

    /**
     * @return default pipeline of built in lowerings, in canonical order
     */
    public static @NotNull LowerPipeline defaults() {
        return new LowerPipeline(List.of(
                TwrCatchSplitter.instance()
        ));
    }

    /**
     * @param unit parsed unit
     * @return unit after running every pass in order
     */
    public @NotNull CompilationUnit run(@NotNull CompilationUnit unit) {
        CompilationUnit cur = unit;
        for (LowerPass pass : passes) cur = pass.lower(cur);
        return cur;
    }
}
