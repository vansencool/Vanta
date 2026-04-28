package net.vansencool.vanta.codegen.classes.visitor;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Records all {@link MethodVisitor} calls as a replay list, then replays them
 * onto a real visitor via {@link #replayInto(MethodVisitor)}. Used as a buffer
 * for parallel method compilation so worker threads can generate bytecode
 * concurrently and the main thread flushes in source order.
 */
public final class RecordingMethodVisitor extends MethodVisitor {

    private final @NotNull List<Consumer<MethodVisitor>> ops = new ArrayList<>();

    public RecordingMethodVisitor() {
        super(Opcodes.ASM9);
    }

    /**
     * Replays all recorded calls onto {@code target} in the order they were recorded.
     */
    public void replayInto(@NotNull MethodVisitor target) {
        for (Consumer<MethodVisitor> op : ops) op.accept(target);
    }

    @Override
    public void visitParameter(String name, int access) {
        ops.add(mv -> mv.visitParameter(name, access));
    }

    @Override
    public @NotNull AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        RecordingAnnotationVisitor rec = new RecordingAnnotationVisitor();
        ops.add(mv -> rec.replayInto(mv.visitAnnotation(descriptor, visible)));
        return rec;
    }

    @Override
    public void visitCode() {
        ops.add(MethodVisitor::visitCode);
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        Object[] localCopy = local == null ? null : local.clone();
        Object[] stackCopy = stack == null ? null : stack.clone();
        ops.add(mv -> mv.visitFrame(type, numLocal, localCopy, numStack, stackCopy));
    }

    @Override
    public void visitInsn(int opcode) {
        ops.add(mv -> mv.visitInsn(opcode));
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        ops.add(mv -> mv.visitIntInsn(opcode, operand));
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        ops.add(mv -> mv.visitVarInsn(opcode, varIndex));
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        ops.add(mv -> mv.visitTypeInsn(opcode, type));
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        ops.add(mv -> mv.visitFieldInsn(opcode, owner, name, descriptor));
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        ops.add(mv -> mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface));
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        Object[] argsCopy = bootstrapMethodArguments.clone();
        ops.add(mv -> mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, argsCopy));
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        ops.add(mv -> mv.visitJumpInsn(opcode, label));
    }

    @Override
    public void visitLabel(Label label) {
        ops.add(mv -> mv.visitLabel(label));
    }

    @Override
    public void visitLdcInsn(Object value) {
        ops.add(mv -> mv.visitLdcInsn(value));
    }

    @Override
    public void visitIincInsn(int varIndex, int increment) {
        ops.add(mv -> mv.visitIincInsn(varIndex, increment));
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        Label[] labelsCopy = labels.clone();
        ops.add(mv -> mv.visitTableSwitchInsn(min, max, dflt, labelsCopy));
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        int[] keysCopy = keys.clone();
        Label[] labelsCopy = labels.clone();
        ops.add(mv -> mv.visitLookupSwitchInsn(dflt, keysCopy, labelsCopy));
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        ops.add(mv -> mv.visitMultiANewArrayInsn(descriptor, numDimensions));
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        ops.add(mv -> mv.visitTryCatchBlock(start, end, handler, type));
    }

    @Override
    public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
        ops.add(mv -> mv.visitLocalVariable(name, descriptor, signature, start, end, index));
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        ops.add(mv -> mv.visitLineNumber(line, start));
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        ops.add(mv -> mv.visitMaxs(maxStack, maxLocals));
    }

    @Override
    public void visitEnd() {
        ops.add(MethodVisitor::visitEnd);
    }
}
