package net.vansencool.vanta.symbol.type.ast;

import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import net.vansencool.vanta.symbol.type.ref.build.RefFromAst;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AstTypeParameterSymbol implements TypeParameterSymbol {

    private final @NotNull TypeParameter declaration;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull Set<String> outerTypeVariables;
    private @NotNull List<TypeRef> cachedBounds;

    public AstTypeParameterSymbol(@NotNull TypeParameter declaration, @NotNull TypeResolver typeResolver, @NotNull Set<String> outerTypeVariables) {
        this.declaration = declaration;
        this.typeResolver = typeResolver;
        this.outerTypeVariables = outerTypeVariables;
        this.cachedBounds = List.of();
    }

    @Override
    public @NotNull String name() {
        return declaration.name();
    }

    @Override
    public @NotNull List<TypeRef> bounds() {
        if (!cachedBounds.isEmpty()) return cachedBounds;
        if (declaration.bounds() == null || declaration.bounds().isEmpty()) {
            cachedBounds = List.of(TypeRefs.ofObject("java/lang/Object"));
            return cachedBounds;
        }
        List<TypeRef> built = new ArrayList<>(declaration.bounds().size());
        for (TypeNode b : declaration.bounds()) built.add(RefFromAst.from(b, typeResolver, outerTypeVariables));
        cachedBounds = List.copyOf(built);
        return cachedBounds;
    }
}
