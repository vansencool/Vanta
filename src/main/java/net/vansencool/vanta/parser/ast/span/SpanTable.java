package net.vansencool.vanta.parser.ast.span;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Identity keyed maps from AST nodes and type nodes to their source {@link Span}.
 */
public final class SpanTable {

    private final @NotNull Map<AstNode, Span> nodeSpans = new IdentityHashMap<>();
    private final @NotNull Map<TypeNode, Span> typeSpans = new IdentityHashMap<>();

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
     * Records {@code span} for {@code node}.
     */
    public void record(@NotNull AstNode node, @NotNull Span span) {
        nodeSpans.put(node, span);
    }

    /**
     * Records {@code span} for {@code type}.
     */
    public void record(@NotNull TypeNode type, @NotNull Span span) {
        typeSpans.put(type, span);
    }
}
