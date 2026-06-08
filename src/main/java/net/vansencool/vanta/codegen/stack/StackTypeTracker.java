package net.vansencool.vanta.codegen.stack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Abstract operand stack mirroring the JVM's reference slots at compile time.
 */
public final class StackTypeTracker {

    public static final @NotNull String PRIM = "$prim";
    public static final @NotNull String NULL = "$null";
    public static final @NotNull String UNKNOWN = "$?";

    private final @NotNull Deque<String> stack = new ArrayDeque<>();

    public void clear() {
        stack.clear();
    }

    public int depth() {
        return stack.size();
    }

    public void push(@NotNull String type) {
        stack.push(type);
    }

    public @Nullable String pop() {
        return stack.isEmpty() ? null : stack.pop();
    }

    public @Nullable String peek() {
        return stack.peek();
    }

    public boolean topIsExactly(@NotNull String internalName) {
        return internalName.equals(peek());
    }

    public void replaceTop(@NotNull String internalName) {
        if (stack.isEmpty()) return;
        stack.pop();
        stack.push(internalName);
    }

    public void dupTop() {
        String t = peek();
        if (t != null) stack.push(t);
    }

    public void swapTop() {
        if (stack.size() < 2) return;
        String a = stack.pop();
        String b = stack.pop();
        stack.push(a);
        stack.push(b);
    }
}
