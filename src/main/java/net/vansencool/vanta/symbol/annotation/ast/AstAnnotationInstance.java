package net.vansencool.vanta.symbol.annotation.ast;

import net.vansencool.vanta.parser.ast.declaration.AnnotationNode;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.annotation.AnnotationInstance;
import net.vansencool.vanta.symbol.annotation.RetentionPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source backed annotation use. Attribute values stay as raw {@link Expression}
 * trees in {@link #values()}; callers that emit bytecode walk those expressions
 * with their own annotation visitor logic. Retention is unknown at the source
 * level without resolving the annotation type.
 */
public final class AstAnnotationInstance implements AnnotationInstance {

    private final @NotNull AnnotationNode node;
    private final @NotNull String internalName;
    private @Nullable Map<String, Object> cachedValues;

    public AstAnnotationInstance(@NotNull AnnotationNode node, @NotNull TypeResolver typeResolver) {
        this.node = node;
        this.internalName = resolveInternal(node, typeResolver);
    }

    private static @NotNull String resolveInternal(@NotNull AnnotationNode node, @NotNull TypeResolver typeResolver) {
        String name = node.name();
        if (name.contains(".")) return name.replace('.', '/');
        return typeResolver.resolveInternalName(new TypeNode(name, null, 0, node.line()));
    }

    public @NotNull AnnotationNode node() {
        return node;
    }

    @Override
    public @NotNull String internalName() {
        return internalName;
    }

    @Override
    public @NotNull Map<String, Object> values() {
        if (cachedValues != null) return cachedValues;
        Map<String, Expression> attrs = node.attributes();
        if (attrs == null || attrs.isEmpty()) {
            cachedValues = Map.of();
            return cachedValues;
        }
        Map<String, Object> out = new LinkedHashMap<>(attrs.size());
        out.putAll(attrs);
        cachedValues = Map.copyOf(out);
        return cachedValues;
    }

    @Override
    public @Nullable RetentionPolicy retention() {
        return null;
    }
}
