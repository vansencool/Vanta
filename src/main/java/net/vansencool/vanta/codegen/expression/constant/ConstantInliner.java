package net.vansencool.vanta.codegen.expression.constant;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.resolver.type.ResolvedType;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.field.reflection.ReflectionFieldSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * Resolves a static final field reference to its compile time value and
 * pushes that value directly onto the stack, avoiding a runtime
 * {@code GETSTATIC}. Mirrors the inlining javac performs for fields that
 * carry a {@code ConstantValue} attribute.
 */
public final class ConstantInliner {

    private final @NotNull ExpressionGenerator exprGen;

    public ConstantInliner(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    public boolean tryInline(@NotNull String ownerInternal, @NotNull String fieldName) {
        MethodContext ctx = exprGen.ctx();
        Object value = lookupNestedConstant(ctx, ownerInternal, fieldName);
        if (value == null) value = lookupSymbolConstant(ctx, ownerInternal, fieldName);
        if (value == null) return false;
        emitConstant(ctx.mv(), value);
        return true;
    }

    private @Nullable Object lookupNestedConstant(@NotNull MethodContext ctx, @NotNull String ownerInternal, @NotNull String fieldName) {
        if (ctx.nestedClassConstants() == null) return null;
        Map<String, Object> consts = ctx.nestedClassConstants().get(ownerInternal);
        return consts != null ? consts.get(fieldName) : null;
    }

    private @Nullable Object lookupSymbolConstant(@NotNull MethodContext ctx, @NotNull String ownerInternal, @NotNull String fieldName) {
        TypeSymbol owner = ctx.methodResolver().classpathManager().typeRegistry().lookup(ownerInternal);
        if (owner == null) return null;
        FieldSymbol field = findField(owner, fieldName);
        if (field == null) return null;
        if (!field.isStatic() || !field.isFinal()) return null;
        TypeRef type = field.type();
        if (!type.isPrimitive() && !"java/lang/String".equals(type.internalName())) return null;
        if (field instanceof ReflectionFieldSymbol reflectionField) {
            Class<?> declaring = reflectionField.reflective().getDeclaringClass();
            if (!exprGen.constantEvaluator().hasConstantValueAttribute(declaring, fieldName)) return null;
        }
        return field.constantValue();
    }

    private @Nullable FieldSymbol findField(@NotNull TypeSymbol owner, @NotNull String fieldName) {
        for (FieldSymbol f : owner.fields()) {
            if (f.name().equals(fieldName)) return f;
        }
        for (TypeSymbol iface : owner.interfaces()) {
            FieldSymbol found = findField(iface, fieldName);
            if (found != null) return found;
        }
        TypeSymbol sup = owner.superclass();
        if (sup != null) return findField(sup, fieldName);
        return null;
    }

    private void emitConstant(@NotNull MethodVisitor mv, @NotNull Object value) {
        Long widenedIntegral = null;
        if (value instanceof Integer i) widenedIntegral = i.longValue();
        else if (value instanceof Byte b) widenedIntegral = b.longValue();
        else if (value instanceof Short s) widenedIntegral = s.longValue();
        else if (value instanceof Character c) widenedIntegral = (long) c;
        if (widenedIntegral != null) {
            ResolvedType expected = exprGen.currentExpected();
            String exp = expected != null && expected.isPrimitive() ? expected.descriptor() : null;
            if ("J".equals(exp)) {
                emitLong(mv, widenedIntegral);
                exprGen.markInlinePushedWidened();
                return;
            }
            if ("F".equals(exp)) {
                emitFloat(mv, widenedIntegral.floatValue());
                exprGen.markInlinePushedWidened();
                return;
            }
            if ("D".equals(exp)) {
                emitDouble(mv, widenedIntegral.doubleValue());
                exprGen.markInlinePushedWidened();
                return;
            }
            OpcodeUtils.pushInt(mv, widenedIntegral.intValue());
            return;
        }
        if (value instanceof Boolean bo) mv.visitInsn(bo ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        else if (value instanceof Long l) emitLong(mv, l);
        else if (value instanceof Float fl) emitFloat(mv, fl);
        else if (value instanceof Double d) emitDouble(mv, d);
        else if (value instanceof String str) mv.visitLdcInsn(str);
    }

    private void emitLong(@NotNull MethodVisitor mv, long l) {
        if (l == 0L) mv.visitInsn(Opcodes.LCONST_0);
        else if (l == 1L) mv.visitInsn(Opcodes.LCONST_1);
        else mv.visitLdcInsn(l);
    }

    private void emitFloat(@NotNull MethodVisitor mv, float fl) {
        if (fl == 0.0f) mv.visitInsn(Opcodes.FCONST_0);
        else if (fl == 1.0f) mv.visitInsn(Opcodes.FCONST_1);
        else if (fl == 2.0f) mv.visitInsn(Opcodes.FCONST_2);
        else mv.visitLdcInsn(fl);
    }

    private void emitDouble(@NotNull MethodVisitor mv, double d) {
        if (d == 0.0) mv.visitInsn(Opcodes.DCONST_0);
        else if (d == 1.0) mv.visitInsn(Opcodes.DCONST_1);
        else mv.visitLdcInsn(d);
    }
}
