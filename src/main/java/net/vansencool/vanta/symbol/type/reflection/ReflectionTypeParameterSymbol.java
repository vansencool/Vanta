package net.vansencool.vanta.symbol.type.reflection;

import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.build.RefFromReflection;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

public final class ReflectionTypeParameterSymbol implements TypeParameterSymbol {

    private final @NotNull TypeVariable<?> typeVariable;
    private @NotNull List<TypeRef> cachedBounds;

    public ReflectionTypeParameterSymbol(@NotNull TypeVariable<?> typeVariable) {
        this.typeVariable = typeVariable;
        this.cachedBounds = List.of();
    }

    @Override
    public @NotNull String name() {
        return typeVariable.getName();
    }

    @Override
    public @NotNull List<TypeRef> bounds() {
        if (!cachedBounds.isEmpty()) return cachedBounds;
        Type[] reflectionBounds = typeVariable.getBounds();
        List<TypeRef> built = new ArrayList<>(reflectionBounds.length);
        for (Type t : reflectionBounds) built.add(RefFromReflection.from(t));
        cachedBounds = List.copyOf(built);
        return cachedBounds;
    }
}
