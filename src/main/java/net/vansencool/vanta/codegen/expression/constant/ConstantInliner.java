package net.vansencool.vanta.codegen.expression.constant;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.FieldInfo;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Resolves a static final field reference to its compile time value and
 * pushes that value directly onto the stack, avoiding a runtime
 * {@code GETSTATIC}. Mirrors the inlining javac performs for fields that
 * carry a {@code ConstantValue} attribute.
 */
public final class ConstantInliner {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator
     */
    public ConstantInliner(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * @param ownerInternal owning class internal name
     * @param fieldName     field name
     * @return true when the field resolved to a constant and a push was
     * emitted, false otherwise (caller should fall back to a normal
     * field access)
     */
    public boolean tryInline(@NotNull String ownerInternal, @NotNull String fieldName) {
        MethodContext ctx = exprGen.ctx();
        Object value = null;
        if (ctx.nestedClassConstants() != null) {
            Map<String, Object> consts = ctx.nestedClassConstants().get(ownerInternal);
            if (consts != null && consts.containsKey(fieldName)) {
                value = consts.get(fieldName);
            }
        }
        if (value == null) {
            Class<?> clazz = ctx.methodResolver().classpathManager().loadClass(ownerInternal);
            if (clazz != null) {
                try {
                    Field f;
                    try {
                        f = clazz.getField(fieldName);
                    } catch (NoSuchFieldException nsf) {
                        f = clazz.getDeclaredField(fieldName);
                        f.setAccessible(true);
                    }
                    int mods = f.getModifiers();
                    if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) return false;
                    Class<?> ftype = f.getType();
                    if (!ftype.isPrimitive() && ftype != String.class) return false;
                    if (!exprGen.constantEvaluator().hasConstantValueAttribute(clazz, fieldName)) return false;
                    value = f.get(null);
                } catch (ReflectiveOperationException | LinkageError e) {
                    return false;
                }
            } else {
                AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(ownerInternal);
                if (info == null) return false;
                for (FieldInfo fi : info.fields()) {
                    if (!fi.name().equals(fieldName)) continue;
                    if (!fi.isStatic()) return false;
                    if ((fi.access() & Opcodes.ACC_FINAL) == 0) return false;
                    if (fi.constantValue() == null) return false;
                    value = fi.constantValue();
                    break;
                }
                if (value == null) return false;
            }
        }
        MethodVisitor mv = ctx.mv();
        if (value instanceof Integer i) OpcodeUtils.pushInt(mv, i);
        else if (value instanceof Byte b) OpcodeUtils.pushInt(mv, b.intValue());
        else if (value instanceof Short s) OpcodeUtils.pushInt(mv, s.intValue());
        else if (value instanceof Character c) OpcodeUtils.pushInt(mv, c);
        else if (value instanceof Boolean bo) mv.visitInsn(bo ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        else if (value instanceof Long l) {
            if (l == 0L) mv.visitInsn(Opcodes.LCONST_0);
            else if (l == 1L) mv.visitInsn(Opcodes.LCONST_1);
            else mv.visitLdcInsn(l);
        } else if (value instanceof Float fl) {
            if (fl == 0.0f) mv.visitInsn(Opcodes.FCONST_0);
            else if (fl == 1.0f) mv.visitInsn(Opcodes.FCONST_1);
            else if (fl == 2.0f) mv.visitInsn(Opcodes.FCONST_2);
            else mv.visitLdcInsn(fl);
        } else if (value instanceof Double d) {
            if (d == 0.0) mv.visitInsn(Opcodes.DCONST_0);
            else if (d == 1.0) mv.visitInsn(Opcodes.DCONST_1);
            else mv.visitLdcInsn(d);
        } else if (value instanceof String str) {
            mv.visitLdcInsn(str);
        } else {
            return false;
        }
        return true;
    }
}
