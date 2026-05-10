package net.vansencool.vanta.symbol.type.asm;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.annotation.AnnotationInstance;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.field.asm.AsmFieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.method.asm.AsmMethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public final class AsmTypeSymbol implements TypeSymbol {

    private final @NotNull AsmClassInfo info;
    private final @NotNull TypeRegistry registry;
    private @Nullable List<MethodSymbol> cachedMethods;
    private @Nullable List<FieldSymbol> cachedFields;
    private @Nullable List<TypeSymbol> cachedInterfaces;

    public AsmTypeSymbol(@NotNull AsmClassInfo info, @NotNull TypeRegistry registry) {
        this.info = info;
        this.registry = registry;
    }

    @Override
    public @NotNull String internalName() {
        return info.internalName();
    }

    @Override
    public @Nullable TypeSymbol superclass() {
        String sc = info.superInternalName();
        return sc != null ? registry.lookup(sc) : null;
    }

    @Override
    public @NotNull List<TypeSymbol> interfaces() {
        if (cachedInterfaces != null) return cachedInterfaces;
        String[] names = info.interfaceInternalNames();
        if (names.length == 0) {
            cachedInterfaces = List.of();
            return cachedInterfaces;
        }
        List<TypeSymbol> out = new ArrayList<>(names.length);
        for (String n : names) {
            TypeSymbol sym = registry.lookup(n);
            if (sym != null) out.add(sym);
        }
        cachedInterfaces = List.copyOf(out);
        return cachedInterfaces;
    }

    @Override
    public int access() {
        return info.access();
    }

    @Override
    public boolean isInterface() {
        return info.isInterface();
    }

    @Override
    public boolean isEnum() {
        return (info.access() & Opcodes.ACC_ENUM) != 0;
    }

    @Override
    public boolean isRecord() {
        return (info.access() & Opcodes.ACC_RECORD) != 0;
    }

    @Override
    public boolean isAbstract() {
        return (info.access() & Opcodes.ACC_ABSTRACT) != 0;
    }

    @Override
    public @NotNull List<MethodSymbol> methods() {
        if (cachedMethods != null) return cachedMethods;
        List<MethodSymbol> out = new ArrayList<>(info.methods().size());
        for (AsmClassInfo.MethodInfo m : info.methods()) {
            out.add(new AsmMethodSymbol(m, this));
        }
        cachedMethods = List.copyOf(out);
        return cachedMethods;
    }

    @Override
    public @NotNull List<FieldSymbol> fields() {
        if (cachedFields != null) return cachedFields;
        List<FieldSymbol> out = new ArrayList<>(info.fields().size());
        for (AsmClassInfo.FieldInfo f : info.fields()) {
            out.add(new AsmFieldSymbol(f, this));
        }
        cachedFields = List.copyOf(out);
        return cachedFields;
    }

    @Override
    public @NotNull List<TypeSymbol> nestedTypes() {
        return List.of();
    }

    @Override
    public @NotNull List<TypeParameterSymbol> typeParameters() {
        return List.of();
    }

    @Override
    public @NotNull List<AnnotationInstance> annotations() {
        return List.of();
    }

    @Override
    public @Nullable Position sourcePosition() {
        return null;
    }
}
