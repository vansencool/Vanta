package net.vansencool.vanta.codegen.statement.tryblock;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.codegen.context.MethodContext;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits the {@code close()} call sequences javac synthesises for try with
 * resources: a normal close on success, and a suppressed exception close on
 * abrupt completion that wraps each {@code close()} in its own try catch.
 */
public final class ResourceCloseEmitter {

    private final @NotNull MethodContext ctx;

    /**
     * @param ctx owning method context
     */
    public ResourceCloseEmitter(@NotNull MethodContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits a {@code close()} on each resource in reverse declaration order,
     * skipping nulls.
     *
     * @param mv                       method visitor to emit into
     * @param resourceSlots            local slot for each resource
     * @param resourceOwners           owning class internal name for each resource
     * @param resourceOwnerIsInterface true when {@code INVOKEINTERFACE} should be used
     */
    public void emitClose(@NotNull MethodVisitor mv, int @NotNull [] resourceSlots, @NotNull String @NotNull [] resourceOwners, boolean @NotNull [] resourceOwnerIsInterface) {
        for (int i = resourceSlots.length - 1; i >= 0; i--) {
            Label skipClose = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, resourceSlots[i]);
            mv.visitJumpInsn(Opcodes.IFNULL, skipClose);
            mv.visitVarInsn(Opcodes.ALOAD, resourceSlots[i]);
            int opcode = resourceOwnerIsInterface[i] ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            mv.visitMethodInsn(opcode, resourceOwners[i], "close", "()V", resourceOwnerIsInterface[i]);
            mv.visitLabel(skipClose);
        }
    }

    /**
     * Emits the try with resources abrupt completion close path: for each
     * resource in reverse declaration order, wraps {@code close()} in a try
     * catch that calls {@code primary.addSuppressed(secondary)} when the
     * close itself throws, so both exceptions are preserved on rethrow.
     *
     * @param mv                       method visitor to emit into
     * @param resourceSlots            local slot for each resource
     * @param resourceOwners           owning class internal name for each resource
     * @param resourceOwnerIsInterface true when {@code INVOKEINTERFACE} should be used
     * @param primaryExSlot            local slot holding the primary exception
     */
    public void emitCloseWithSuppressed(@NotNull MethodVisitor mv, int @NotNull [] resourceSlots, @NotNull String @NotNull [] resourceOwners, boolean @NotNull [] resourceOwnerIsInterface, int primaryExSlot) {
        for (int i = resourceSlots.length - 1; i >= 0; i--) {
            Label skipClose = new Label();
            Label closeStart = new Label();
            Label closeEnd = new Label();
            Label closeCatch = new Label();
            Label afterClose = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, resourceSlots[i]);
            mv.visitJumpInsn(Opcodes.IFNULL, skipClose);
            mv.visitTryCatchBlock(closeStart, closeEnd, closeCatch, "java/lang/Throwable");
            mv.visitLabel(closeStart);
            mv.visitVarInsn(Opcodes.ALOAD, resourceSlots[i]);
            int opcode = resourceOwnerIsInterface[i] ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            mv.visitMethodInsn(opcode, resourceOwners[i], "close", "()V", resourceOwnerIsInterface[i]);
            mv.visitLabel(closeEnd);
            mv.visitJumpInsn(Opcodes.GOTO, afterClose);
            mv.visitLabel(closeCatch);
            mv.visitVarInsn(Opcodes.ALOAD, primaryExSlot);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "addSuppressed", "(Ljava/lang/Throwable;)V", false);
            mv.visitLabel(afterClose);
            mv.visitLabel(skipClose);
        }
    }

    /**
     * @param internalName resource owner internal name
     * @return true when {@code close()} should dispatch via
     * {@code INVOKEINTERFACE}; false for {@code INVOKEVIRTUAL}.
     * Defaults to interface dispatch when the class cannot be loaded
     */
    public boolean isResourceOwnerInterface(@NotNull String internalName) {
        if ("java/lang/AutoCloseable".equals(internalName) || "java/io/Closeable".equals(internalName)) return true;
        Class<?> cls = ctx.methodResolver().classpathManager().loadClass(internalName);
        if (cls != null) return cls.isInterface();
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(internalName);
        if (info != null) return info.isInterface();
        return true;
    }
}
