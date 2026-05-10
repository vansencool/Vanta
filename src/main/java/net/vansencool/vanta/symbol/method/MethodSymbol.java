package net.vansencool.vanta.symbol.method;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface MethodSymbol {

    @NotNull String name();

    @NotNull String descriptor();

    int access();

    boolean isStatic();

    boolean isAbstract();

    boolean isVarargs();

    boolean isBridge();

    boolean isSynthetic();

    @NotNull List<TypeRef> parameterTypes();

    @NotNull TypeRef returnType();

    @NotNull List<TypeParameterSymbol> typeParameters();

    @Nullable Position sourcePosition();

    @NotNull TypeSymbol owner();
}
