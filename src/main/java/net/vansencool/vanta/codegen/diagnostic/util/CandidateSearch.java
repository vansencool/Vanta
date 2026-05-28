package net.vansencool.vanta.codegen.diagnostic.util;

import net.vansencool.vanta.symbol.registry.TypeRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Locates source registered types whose simple name matches a requested identifier.
 */
public final class CandidateSearch {

    private CandidateSearch() {
    }

    /**
     * @param registry   type registry holding source registered classes
     * @param simpleName identifier the user wrote
     * @return internal names of every registered type whose simple name matches
     */
    public static @NotNull List<String> sameSimpleName(@NotNull TypeRegistry registry, @NotNull String simpleName) {
        List<String> out = new ArrayList<>();
        for (String internalName : registry.astSourceNames()) {
            int lastSlash = internalName.lastIndexOf('/');
            String simple = lastSlash < 0 ? internalName : internalName.substring(lastSlash + 1);
            if (simple.equals(simpleName)) out.add(internalName);
        }
        return out;
    }
}
