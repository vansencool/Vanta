package net.vansencool.vanta.codegen.context;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks break and continue label targets for loops and switch statements.
 * Supports labeled statements by mapping labels to their loop context.
 */
public final class LabelContext {

    private final @NotNull Deque<LoopLabels> loopStack;
    private final @NotNull Map<String, LoopLabels> namedLabels;

    /**
     * Creates a new empty label context.
     */
    public LabelContext() {
        this.loopStack = new ArrayDeque<>();
        this.namedLabels = new HashMap<>();
    }

    /**
     * Pushes a loop context with break and continue targets.
     *
     * @param breakLabel    the target label for break
     * @param continueLabel the target label for continue, or null if not applicable
     * @param name          the optional label name for this loop
     */
    public void pushLoop(@NotNull Label breakLabel, @Nullable Label continueLabel, @Nullable String name) {
        LoopLabels labels = new LoopLabels(breakLabel, continueLabel);
        loopStack.push(labels);
        if (name != null) {
            namedLabels.put(name, labels);
        }
    }

    /**
     * Pops the current loop context.
     *
     * @param name the optional label name to also remove
     */
    public void popLoop(@Nullable String name) {
        loopStack.pop();
        if (name != null) {
            namedLabels.remove(name);
        }
    }

    /**
     * Gets the break label for the innermost loop/switch, or a named label.
     *
     * @param label the label name, or null for the innermost
     * @return the break label
     */
    public @NotNull Label breakLabel(@Nullable String label) {
        if (label != null) {
            LoopLabels named = namedLabels.get(label);
            if (named != null) return named.breakLabel;
        }
        return Objects.requireNonNull(loopStack.peek(), "break outside loop").breakLabel;
    }

    /**
     * Gets the continue label for the innermost loop, or a named label.
     *
     * @param label the label name, or null for the innermost
     * @return the continue label, or null if not in a loop
     */
    public @Nullable Label continueLabel(@Nullable String label) {
        if (label != null) {
            LoopLabels named = namedLabels.get(label);
            if (named != null) return named.continueLabel;
        }
        LoopLabels top = loopStack.peek();
        return top != null ? top.continueLabel : null;
    }

    /**
     * Internal record of break/continue label pair.
     */
    private record LoopLabels(@NotNull Label breakLabel, @Nullable Label continueLabel) {
    }
}
