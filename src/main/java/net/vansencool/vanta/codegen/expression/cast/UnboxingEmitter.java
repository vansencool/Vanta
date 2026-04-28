package net.vansencool.vanta.codegen.expression.cast;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits the {@code CHECKCAST} + wrapper-to-primitive unboxing sequence used
 * whenever a value on the stack needs to reach a primitive slot from a
 * reference slot. Skips the {@code CHECKCAST} when the most-recently emitted
 * cast already covers the wrapper, so redundant casts don't compound.
 */
public final class UnboxingEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for last-cast tracking
     *                and wrapper-class lookups
     */
    public UnboxingEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits the unboxing call for {@code targetPrimitive}, first narrowing the
     * reference to {@code wrapperInternal} if the current stack type hasn't
     * already been checkcast to it. When {@code wrapperInternal} is overly
     * generic ({@code Object}/{@code Number}/wildcard), substitutes the
     * primitive's specific wrapper.
     *
     * @param mv              method visitor to emit into
     * @param targetPrimitive target primitive descriptor
     * @param wrapperInternal declared wrapper internal name to unbox through
     */
    public void withCast(@NotNull MethodVisitor mv, @NotNull String targetPrimitive, @NotNull String wrapperInternal) {
        if ("java/lang/Object".equals(wrapperInternal) || "java/lang/Number".equals(wrapperInternal) || wrapperInternal.contains("?")) {
            String specific = exprGen.numericCoercion().wrapperInternalName(targetPrimitive);
            if (specific != null) wrapperInternal = specific;
        }
        if (!exprGen.lastEmittedCheckcast(wrapperInternal)) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, wrapperInternal);
        }
        String method = switch (targetPrimitive) {
            case "I" -> "intValue";
            case "J" -> "longValue";
            case "F" -> "floatValue";
            case "D" -> "doubleValue";
            case "Z" -> "booleanValue";
            case "B" -> "byteValue";
            case "C" -> "charValue";
            case "S" -> "shortValue";
            default -> null;
        };
        if (method != null)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperInternal, method, "()" + targetPrimitive, false);
    }

    /**
     * Emits the unboxing sequence when a {@code return} expression produced a
     * boxed wrapper but the method signature demands a primitive result. Uses
     * the actual source type's wrapper class when known (so {@code Character}
     * unboxes via {@code charValue}) and falls back to the target primitive's
     * wrapper when the source is raw {@code Object}.
     *
     * @param mv     method visitor to emit into
     * @param source source type currently on the stack
     * @param target target primitive type the method returns
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
     * Convenience overload that unboxes without caller-declared source.
     *
     * @param mv              method visitor to emit into
     * @param targetPrimitive target primitive descriptor
     */
    public void emit(@NotNull MethodVisitor mv, @NotNull String targetPrimitive) {
        emit(mv, targetPrimitive, null);
    }

    /**
     * Emits the wrapper-to-primitive call directly, narrowing via
     * {@code CHECKCAST} when the declared source doesn't already match the
     * wrapper.
     *
     * @param mv              method visitor to emit into
     * @param targetPrimitive target primitive descriptor
     * @param sourceInternal  declared source wrapper internal name, or null
     */
    public void emit(@NotNull MethodVisitor mv, @NotNull String targetPrimitive, @Nullable String sourceInternal) {
        switch (targetPrimitive) {
            case "I" -> {
                if (!"java/lang/Integer".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Integer"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            }
            case "J" -> {
                if (!"java/lang/Long".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Long"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
            }
            case "F" -> {
                if (!"java/lang/Float".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Float"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
            }
            case "D" -> {
                if (!"java/lang/Double".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Double"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
            }
            case "Z" -> {
                if (!"java/lang/Boolean".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Boolean"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            }
            case "B" -> {
                if (!"java/lang/Byte".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Byte"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Byte");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
            }
            case "C" -> {
                if (!"java/lang/Character".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Character"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
            }
            case "S" -> {
                if (!"java/lang/Short".equals(sourceInternal) && !exprGen.lastEmittedCheckcast("java/lang/Short"))
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Short");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
            }
        }
    }
}
