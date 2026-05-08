package net.vansencool.vanta.symbol.type;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A generic type parameter declared on a class or method. Bounds are
 * preserved so generic substitution can validate them downstream.
 */
public interface TypeParameterSymbol {

    @NotNull String name();

    @NotNull List<TypeRef> bounds();
}
