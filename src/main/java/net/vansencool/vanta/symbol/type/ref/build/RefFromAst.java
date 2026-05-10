package net.vansencool.vanta.symbol.type.ref.build;

import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link TypeRef} instances from AST {@link TypeNode}s. Type variable
 * names registered on the enclosing type or method are recognised and emit
 * a type variable ref, with erasure resolved against the supplied bound map
 * so generated descriptors match javac's erasure rules.
 */
public final class RefFromAst {

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void");

    private RefFromAst() {
    }

    public static @NotNull TypeRef from(@NotNull TypeNode node, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables) {
        return from(node, typeResolver, typeVariables, Map.of());
    }

    public static @NotNull TypeRef from(@NotNull TypeNode node, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @NotNull Map<String, String> typeVariableErasures) {
        TypeRef base = baseFrom(node, typeResolver, typeVariables, typeVariableErasures);
        return node.arrayDimensions() > 0 ? TypeRefs.ofArray(base, node.arrayDimensions()) : base;
    }

    private static @NotNull TypeRef baseFrom(@NotNull TypeNode node, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @NotNull Map<String, String> typeVariableErasures) {
        String name = node.name();
        if (PRIMITIVES.contains(name)) {
            return TypeRefs.ofPrimitive(primitiveDescriptor(name));
        }
        if (typeVariables.contains(name)) {
            String erasure = typeVariableErasures.get(name);
            return erasure != null ? TypeRefs.ofTypeVariable(name, erasure) : TypeRefs.ofTypeVariable(name);
        }
        ResolvedType resolved = typeResolver.resolve(node.arrayDimensions() == 0 ? node : new TypeNode(name, node.typeArguments(), 0, node.line()));
        String internal = resolved.internalName() != null ? resolved.internalName() : name.replace('.', '/');
        if (node.typeArguments() == null || node.typeArguments().isEmpty()) {
            return TypeRefs.ofObject(internal);
        }
        List<TypeRef> args = new ArrayList<>(node.typeArguments().size());
        for (TypeNode arg : node.typeArguments()) {
            args.add(from(arg, typeResolver, typeVariables, typeVariableErasures));
        }
        return TypeRefs.ofObject(internal, args);
    }

    private static @NotNull String primitiveDescriptor(@NotNull String name) {
        return switch (name) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "short" -> "S";
            case "char" -> "C";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "void" -> "V";
            default -> "L" + name + ";";
        };
    }
}
