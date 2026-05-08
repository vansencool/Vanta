package net.vansencool.vanta.symbol.type;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A class, interface, enum, record, or annotation declaration. The Vanta
 * resolver and codegen consult symbols instead of reflection so the same code
 * paths handle source declarations parsed by Vanta, real classpath classes,
 * and stub class file bytes uniformly.
 */
public interface TypeSymbol {

    @NotNull String internalName();

    @Nullable TypeSymbol superclass();

    @NotNull List<TypeSymbol> interfaces();

    int access();

    boolean isInterface();

    boolean isEnum();

    boolean isRecord();

    boolean isAbstract();

    @NotNull List<MethodSymbol> methods();

    @NotNull List<FieldSymbol> fields();

    @NotNull List<TypeSymbol> nestedTypes();

    @NotNull List<TypeParameterSymbol> typeParameters();

    @Nullable Position sourcePosition();
}
