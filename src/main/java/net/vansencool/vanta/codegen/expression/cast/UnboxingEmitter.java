package net.vansencool.vanta.codegen.expression.cast;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * Emits the {@code CHECKCAST} + wrapper-to-primitive unboxing sequence used
 * whenever a value on the stack needs to reach a primitive slot from a
 * reference slot.
 */
public final class UnboxingEmitter {

    private static final Map<String, UnboxRecipe> BY_TARGET = Map.of(
            "I", new UnboxRecipe("java/lang/Integer", "intValue", "()I"),
            "J", new UnboxRecipe("java/lang/Long", "longValue", "()J"),
            "F", new UnboxRecipe("java/lang/Float", "floatValue", "()F"),
            "D", new UnboxRecipe("java/lang/Double", "doubleValue", "()D"),
            "Z", new UnboxRecipe("java/lang/Boolean", "booleanValue", "()Z"),
            "B", new UnboxRecipe("java/lang/Byte", "byteValue", "()B"),
            "C", new UnboxRecipe("java/lang/Character", "charValue", "()C"),
            "S", new UnboxRecipe("java/lang/Short", "shortValue", "()S")
    );

    private static final Map<String, UnboxRecipe> WIDENS_TO_INT = Map.of(
            "java/lang/Byte", BY_TARGET.get("B"),
            "java/lang/Short", BY_TARGET.get("S"),
            "java/lang/Character", BY_TARGET.get("C")
    );

    private final @NotNull ExpressionGenerator exprGen;

    public UnboxingEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Picks the wrapper, method, and descriptor for {@code targetPrimitive}.
     * For an {@code int} target whose source is {@code Byte}/{@code Short}/
     * {@code Character}, picks that wrapper's own unbox so the sub int result
     * widens to int on the JVM stack naturally.
     */
    private static @Nullable UnboxRecipe pickRecipe(@NotNull String targetPrimitive, @Nullable String sourceInternal) {
        if ("I".equals(targetPrimitive)) {
            UnboxRecipe forSource = WIDENS_TO_INT.get(sourceInternal);
            if (forSource != null) return forSource;
        }
        return BY_TARGET.get(targetPrimitive);
    }

    /**
     * Emits the unboxing call for {@code targetPrimitive}, first narrowing the
     * reference to {@code wrapperInternal} when the current stack type hasn't
     * already been checkcast to it.
     */
    public void withCast(@NotNull MethodVisitor mv, @NotNull String targetPrimitive, @NotNull String wrapperInternal) {
        withCast(mv, targetPrimitive, wrapperInternal, null);
    }

    /**
     * @param operandActual already known static type of the operand on the stack
     */
    public void withCast(@NotNull MethodVisitor mv, @NotNull String targetPrimitive, @NotNull String wrapperInternal, @Nullable String operandActual) {
        if ("java/lang/Object".equals(wrapperInternal) || "java/lang/Number".equals(wrapperInternal) || wrapperInternal.contains("?")) {
            String specific = exprGen.numericCoercion().wrapperInternalName(targetPrimitive);
            if (specific != null) wrapperInternal = specific;
        }
        boolean alreadyTyped = wrapperInternal.equals(operandActual) || exprGen.ctx().stackTracker().topIsExactly(wrapperInternal);
        boolean checkcastEmitted = exprGen.lastEmittedCheckcast(wrapperInternal);
        if (!alreadyTyped && !checkcastEmitted) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperInternal);
        }
        UnboxRecipe recipe = BY_TARGET.get(targetPrimitive);
        if (recipe != null) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperInternal, recipe.method, recipe.descriptor, false);
        }
    }

    /**
     * Emits the unboxing sequence when a {@code return} expression produced a
     * boxed wrapper but the method signature demands a primitive result.
     */
    public void forReturn(@NotNull MethodVisitor mv, @NotNull ResolvedType source, @NotNull ResolvedType target) {
        String wrapper = source.internalName();
        if (wrapper == null || "java/lang/Object".equals(wrapper)) {
            String mapped = exprGen.numericCoercion().wrapperInternalName(target.descriptor());
            if (mapped != null) wrapper = mapped;
        }
        if (wrapper == null) {
            emit(mv, target.descriptor());
        } else {
            withCast(mv, target.descriptor(), wrapper);
        }
    }

    /**
     * Convenience overload that unboxes without a caller declared source.
     */
    public void emit(@NotNull MethodVisitor mv, @NotNull String targetPrimitive) {
        emit(mv, targetPrimitive, null);
    }

    /**
     * Emits the wrapper-to-primitive call directly, narrowing via
     * {@code CHECKCAST} when the declared source doesn't already match the
     * picked wrapper.
     */
    public void emit(@NotNull MethodVisitor mv, @NotNull String targetPrimitive, @Nullable String sourceInternal) {
        UnboxRecipe recipe = pickRecipe(targetPrimitive, sourceInternal);
        if (recipe == null) return;
        if (!recipe.wrapper.equals(sourceInternal) && !exprGen.lastEmittedCheckcast(recipe.wrapper) && !exprGen.ctx().stackTracker().topIsExactly(recipe.wrapper)) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, recipe.wrapper);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, recipe.wrapper, recipe.method, recipe.descriptor, false);
    }

    private record UnboxRecipe(@NotNull String wrapper, @NotNull String method, @NotNull String descriptor) {
    }
}
