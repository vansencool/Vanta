package net.vansencool.vanta.resolver.scope;

import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a lexical scope that maps variable names to their resolved types and local indices.
 * Scopes form a chain from inner to outer for nested lookups.
 */
public final class Scope {

    private final @Nullable Scope parent;
    private final @NotNull Map<String, LocalVariable> variables;
    private int nextLocalIndex;

    /**
     * Creates a root scope.
     *
     * @param startIndex the starting local variable index
     */
    public Scope(int startIndex) {
        this.parent = null;
        this.variables = new HashMap<>();
        this.nextLocalIndex = startIndex;
    }

    /**
     * Declares a local variable in this scope.
     *
     * @param name the variable name
     * @param type the resolved type
     * @return the local variable
     */
    public @NotNull LocalVariable declare(@NotNull String name, @NotNull ResolvedType type) {
        int index = nextLocalIndex;
        nextLocalIndex += type.stackSize();
        LocalVariable var = new LocalVariable(name, type, index);
        variables.put(name, var);
        return var;
    }

    /**
     * Looks up a variable by name, searching parent scopes if necessary.
     *
     * @param name the variable name
     * @return the local variable, or null if not found
     */
    public @Nullable LocalVariable resolve(@NotNull String name) {
        LocalVariable var = variables.get(name);
        if (var != null) return var;
        if (parent != null) return parent.resolve(name);
        return null;
    }

    /**
     * @return the current next local index
     */
    public int nextLocalIndex() {
        return nextLocalIndex;
    }

    /**
     * Updates the next local index (for synchronization with parent after child scope closes).
     *
     * @param index the new index
     */
    public void syncNextLocalIndex(int index) {
        this.nextLocalIndex = index;
    }

    /**
     * Removes all variable bindings whose slot index is at or above {@code fromSlot}.
     * Used when a lexical block exits so its declarations no longer resolve in the
     * enclosing scope. Slot indices and shadowed outer bindings are preserved.
     */
    public void removeVariablesFrom(int fromSlot) {
        variables.entrySet().removeIf(e -> e.getValue().index() >= fromSlot);
    }
}
