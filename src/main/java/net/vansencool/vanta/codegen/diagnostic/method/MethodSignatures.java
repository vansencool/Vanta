package net.vansencool.vanta.codegen.diagnostic.method;

import net.vansencool.vanta.codegen.diagnostic.util.StringDistance;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Collects {@link MethodSymbol}s and renders signatures for diagnostic messages.
 */
public final class MethodSignatures {

    private MethodSignatures() {
    }

    /**
     * @return every method named {@code name} reachable from {@code owner} through its super chain
     */
    public static @NotNull List<MethodSymbol> withName(@NotNull TypeSymbol owner, @NotNull String name) {
        List<MethodSymbol> out = new ArrayList<>();
        Set<TypeSymbol> visited = new HashSet<>();
        collectWithName(owner, name, out, visited);
        return out;
    }

    /**
     * @return up to {@code limit} method names on {@code owner} close to {@code wanted} by edit distance
     */
    public static @NotNull List<String> closeNames(@NotNull TypeSymbol owner, @NotNull String wanted, int limit) {
        Set<String> declared = new TreeSet<>();
        Set<TypeSymbol> visited = new HashSet<>();
        collectNames(owner, declared, visited);
        int threshold = Math.max(2, wanted.length() / 2);
        List<Scored> scored = new ArrayList<>(declared.size());
        for (String name : declared) {
            int distance = StringDistance.levenshtein(wanted, name);
            if (distance <= threshold) scored.add(new Scored(name, distance));
        }
        scored.sort((a, b) -> a.distance - b.distance);
        List<String> out = new ArrayList<>(Math.min(limit, scored.size()));
        for (int i = 0; i < scored.size() && i < limit; i++) out.add(scored.get(i).name);
        return out;
    }

    /**
     * @return parenthesised descriptor list, e.g. {@code (Ljava/lang/String;I)}
     */
    public static @NotNull String descriptorList(@NotNull MethodSymbol method) {
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (TypeRef p : method.parameterTypes()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(p.descriptor());
        }
        return sb.append(')').toString();
    }

    private static void collectWithName(@NotNull TypeSymbol owner, @NotNull String name, @NotNull List<MethodSymbol> out, @NotNull Set<TypeSymbol> visited) {
        if (!visited.add(owner)) return;
        for (MethodSymbol m : owner.methods()) {
            if (m.name().equals(name)) out.add(m);
        }
        TypeSymbol sup = owner.superclass();
        if (sup != null) collectWithName(sup, name, out, visited);
        for (TypeSymbol iface : owner.interfaces()) collectWithName(iface, name, out, visited);
    }

    private static void collectNames(@NotNull TypeSymbol owner, @NotNull Set<String> out, @NotNull Set<TypeSymbol> visited) {
        if (!visited.add(owner)) return;
        for (MethodSymbol m : owner.methods()) out.add(m.name());
        TypeSymbol sup = owner.superclass();
        if (sup != null) collectNames(sup, out, visited);
        for (TypeSymbol iface : owner.interfaces()) collectNames(iface, out, visited);
    }

    private record Scored(@NotNull String name, int distance) {
    }
}
