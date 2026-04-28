package net.vansencool.vanta.codegen.classes.visitor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Records {@link AnnotationVisitor} calls for deferred replay, used by
 * {@link RecordingMethodVisitor} to buffer method annotation data.
 */
public final class RecordingAnnotationVisitor extends AnnotationVisitor {

    private final @NotNull List<Consumer<AnnotationVisitor>> ops = new ArrayList<>();

    public RecordingAnnotationVisitor() {
        super(Opcodes.ASM9);
    }

    public void replayInto(@Nullable AnnotationVisitor target) {
        if (target == null) return;
        for (Consumer<AnnotationVisitor> op : ops) op.accept(target);
    }

    @Override
    public void visit(String name, Object value) {
        ops.add(av -> av.visit(name, value));
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
        ops.add(av -> av.visitEnum(name, descriptor, value));
    }

    @Override
    public @NotNull AnnotationVisitor visitAnnotation(String name, String descriptor) {
        RecordingAnnotationVisitor rec = new RecordingAnnotationVisitor();
        ops.add(av -> rec.replayInto(av.visitAnnotation(name, descriptor)));
        return rec;
    }

    @Override
    public @NotNull AnnotationVisitor visitArray(String name) {
        RecordingAnnotationVisitor rec = new RecordingAnnotationVisitor();
        ops.add(av -> rec.replayInto(av.visitArray(name)));
        return rec;
    }

    @Override
    public void visitEnd() {
        ops.add(AnnotationVisitor::visitEnd);
    }
}
