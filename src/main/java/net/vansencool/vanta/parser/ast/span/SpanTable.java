package net.vansencool.vanta.parser.ast.span;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Identity keyed maps from AST nodes (and type nodes, and named sub elements of nodes)
 * to their source {@link Span}.
 */
public final class SpanTable {

    private final @NotNull Map<AstNode, Span> nodeSpans = new IdentityHashMap<>();
    private final @NotNull Map<TypeNode, Span> typeSpans = new IdentityHashMap<>();
    private final @NotNull Map<SubKey, Span> subSpans = new HashMap<>();

    /**
     * @param node target AST node
     * @return recorded span, or null when none
     */
    public @Nullable Span span(@NotNull AstNode node) {
        return nodeSpans.get(node);
    }

    /**
     * @param type target type node
     * @return recorded span, or null when none
     */
    public @Nullable Span span(@NotNull TypeNode type) {
        return typeSpans.get(type);
    }

    /**
     * @param owner AST node the sub element belongs to
     * @param kind  free form label identifying the sub element (e.g. {@code "methodName"})
     * @return recorded sub span, or null when none
     */
    public @Nullable Span sub(@NotNull AstNode owner, @NotNull String kind) {
        return subSpans.get(new SubKey(owner, kind));
    }

    public void record(@NotNull AstNode node, @NotNull Span span) {
        nodeSpans.put(node, span);
    }

    public void record(@NotNull TypeNode type, @NotNull Span span) {
        typeSpans.put(type, span);
    }

    /**
     * Records a span for a named sub element of {@code owner}. Sub elements
     * have no AST identity of their own (a method's name is a {@code String},
     * not a node) so they live on this side map keyed by owner identity and
     * a free form kind label.
     */
    public void recordSub(@NotNull AstNode owner, @NotNull String kind, @NotNull Span span) {
        subSpans.put(new SubKey(owner, kind), span);
    }

    private record SubKey(@NotNull AstNode owner, @NotNull String kind) {

        @Override
        public boolean equals(Object o) {
            return o instanceof SubKey s && s.owner == this.owner && s.kind.equals(this.kind);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(owner) * 31 + kind.hashCode();
        }
    }
}
