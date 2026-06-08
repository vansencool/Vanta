package net.vansencool.vanta.codegen.stack;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * {@link MethodVisitor} delegate that pipes every visited instruction into a
 * {@link StackTypeTracker}.
 */
public final class TrackingMethodVisitor extends MethodVisitor {

    private final @NotNull StackTypeTracker tracker;
    private @NotNull String[] localTypes;

    public TrackingMethodVisitor(@NotNull MethodVisitor delegate, @NotNull StackTypeTracker tracker, int maxLocals) {
        super(Opcodes.ASM9, delegate);
        this.tracker = tracker;
        this.localTypes = new String[Math.max(maxLocals, 64)];
    }

    public @NotNull StackTypeTracker tracker() {
        return tracker;
    }

    public void recordLocal(int slot, @NotNull String internalName) {
        ensureCapacity(slot);
        localTypes[slot] = internalName;
    }

    private void ensureCapacity(int slot) {
        if (slot < localTypes.length) return;
        String[] grown = new String[Math.max(slot + 1, localTypes.length * 2)];
        System.arraycopy(localTypes, 0, grown, 0, localTypes.length);
        localTypes = grown;
    }

    @Override
    public void visitInsn(int opcode) {
        switch (opcode) {
            case Opcodes.ACONST_NULL -> tracker.push(StackTypeTracker.NULL);
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                 Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.FCONST_0,
                 Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1 -> tracker.push(StackTypeTracker.PRIM);
            case Opcodes.DUP -> tracker.dupTop();
            case Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 -> tracker.dupTop();
            case Opcodes.POP, Opcodes.POP2 -> tracker.pop();
            case Opcodes.SWAP -> tracker.swapTop();
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR,
                 Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL,
                 Opcodes.LDIV, Opcodes.LREM, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.LSHL, Opcodes.LSHR,
                 Opcodes.LUSHR, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.DADD,
                 Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM, Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG,
                 Opcodes.DCMPL, Opcodes.DCMPG -> {
                tracker.pop();
                tracker.pop();
                tracker.push(StackTypeTracker.PRIM);
            }
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                 Opcodes.L2I, Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I,
                 Opcodes.D2L, Opcodes.D2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.ARRAYLENGTH -> {
                tracker.pop();
                tracker.push(StackTypeTracker.PRIM);
            }
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> tracker.pop();
            case Opcodes.ATHROW -> tracker.pop();
            case Opcodes.AALOAD -> {
                tracker.pop();
                tracker.pop();
                tracker.push(StackTypeTracker.UNKNOWN);
            }
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
                 Opcodes.SALOAD -> {
                tracker.pop();
                tracker.pop();
                tracker.push(StackTypeTracker.PRIM);
            }
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.BASTORE, Opcodes.CASTORE,
                 Opcodes.SASTORE, Opcodes.AASTORE -> {
                tracker.pop();
                tracker.pop();
                tracker.pop();
            }
            case Opcodes.RETURN, Opcodes.NOP -> {
            }
            case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> tracker.pop();
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        switch (opcode) {
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> tracker.push(StackTypeTracker.PRIM);
            case Opcodes.NEWARRAY -> {
                tracker.pop();
                tracker.push("[" + arrayElementDescriptor(operand));
            }
        }
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> tracker.push(StackTypeTracker.PRIM);
            case Opcodes.ALOAD -> {
                String t = var < localTypes.length && localTypes[var] != null ? localTypes[var] : StackTypeTracker.UNKNOWN;
                tracker.push(t);
            }
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> tracker.pop();
            case Opcodes.ASTORE -> {
                String t = tracker.pop();
                if (t != null) recordLocal(var, t);
            }
        }
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        switch (opcode) {
            case Opcodes.NEW -> tracker.push(type);
            case Opcodes.ANEWARRAY -> {
                tracker.pop();
                tracker.push("[" + (type.startsWith("[") ? type : "L" + type + ";"));
            }
            case Opcodes.CHECKCAST -> tracker.replaceTop(type);
            case Opcodes.INSTANCEOF -> {
                tracker.pop();
                tracker.push(StackTypeTracker.PRIM);
            }
        }
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        switch (opcode) {
            case Opcodes.GETSTATIC -> tracker.push(typeFromDescriptor(descriptor));
            case Opcodes.PUTSTATIC -> tracker.pop();
            case Opcodes.GETFIELD -> {
                tracker.pop();
                tracker.push(typeFromDescriptor(descriptor));
            }
            case Opcodes.PUTFIELD -> {
                tracker.pop();
                tracker.pop();
            }
        }
        super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        int argCount = Type.getArgumentTypes(descriptor).length;
        for (int i = 0; i < argCount; i++) tracker.pop();
        if (opcode != Opcodes.INVOKESTATIC) tracker.pop();
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.VOID) tracker.push(typeFromAsmType(ret));
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        int argCount = Type.getArgumentTypes(descriptor).length;
        for (int i = 0; i < argCount; i++) tracker.pop();
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.VOID) tracker.push(typeFromAsmType(ret));
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitLdcInsn(Object value) {
        if (value instanceof String) tracker.push("java/lang/String");
        else if (value instanceof Type t && t.getSort() == Type.OBJECT) tracker.push("java/lang/Class");
        else tracker.push(StackTypeTracker.PRIM);
        super.visitLdcInsn(value);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE, Opcodes.IFNULL,
                 Opcodes.IFNONNULL -> tracker.pop();
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                 Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                tracker.pop();
                tracker.pop();
            }
            case Opcodes.GOTO -> {
            }
        }
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        tracker.pop();
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        tracker.pop();
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        for (int i = 0; i < numDimensions; i++) tracker.pop();
        tracker.push(descriptor);
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        super.visitIincInsn(var, increment);
    }

    @Override
    public void visitLabel(Label label) {
        super.visitLabel(label);
    }

    private static @NotNull String typeFromDescriptor(@NotNull String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) return descriptor.substring(1, descriptor.length() - 1);
        if (descriptor.startsWith("[")) return descriptor;
        return StackTypeTracker.PRIM;
    }

    private static @NotNull String typeFromAsmType(@NotNull Type type) {
        if (type.getSort() == Type.OBJECT) return type.getInternalName();
        if (type.getSort() == Type.ARRAY) return type.getDescriptor();
        return StackTypeTracker.PRIM;
    }

    private static @NotNull String arrayElementDescriptor(int atype) {
        return switch (atype) {
            case Opcodes.T_BOOLEAN -> "Z";
            case Opcodes.T_CHAR -> "C";
            case Opcodes.T_BYTE -> "B";
            case Opcodes.T_SHORT -> "S";
            case Opcodes.T_INT -> "I";
            case Opcodes.T_LONG -> "J";
            case Opcodes.T_FLOAT -> "F";
            case Opcodes.T_DOUBLE -> "D";
            default -> "Ljava/lang/Object;";
        };
    }
}
