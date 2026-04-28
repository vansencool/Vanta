package net.vansencool.vanta.codegen.expression.field;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;

/**
 * Emits bytecode for field-access expressions: outer-this access
 * ({@code Outer.this}), class literals ({@code Foo.class} including primitive
 * {@code int.class}), static and instance {@code GETFIELD}/{@code GETSTATIC},
 * and the special {@code array.length} instruction. Falls through to a last-
 * resort same-class field access when nothing else resolves.
 */
public final class FieldAccessEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for target emission
     *                and shared resolver / constant-inline helpers
     */
    public FieldAccessEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits a field access.
     *
     * @param fieldAccess field-access node
     * @param expected    slot the field value flows into; used only to guess
     *                    descriptor when the static field owner is on the
     *                    classpath but the field is not resolvable
     */
    public void emit(@NotNull FieldAccessExpression fieldAccess, @Nullable ResolvedType expected) {
        MethodContext ctx = exprGen.ctx();
        if ("this".equals(fieldAccess.fieldName()) && fieldAccess.target() instanceof NameExpression name) {
            String enclosingOuter = ctx.enclosingOuterInternal();
            if (enclosingOuter != null) {
                String outerSimple = enclosingOuter.substring(enclosingOuter.lastIndexOf('/') + 1);
                if (outerSimple.equals(name.name()) || enclosingOuter.replace('/', '.').endsWith("." + name.name())) {
                    ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
                    ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "this$0", "L" + enclosingOuter + ";");
                    return;
                }
            }
        }
        String typePrefix = exprGen.resolveTypePrefix(fieldAccess.target());
        if (typePrefix != null) {
            MethodResolver.ResolvedField staticResolved = ctx.methodResolver().resolveField(typePrefix, fieldAccess.fieldName());
            if (staticResolved != null && staticResolved.isStatic()) {
                if (exprGen.constantInliner().tryInline(staticResolved.owner(), staticResolved.name())) return;
                ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, staticResolved.owner(), staticResolved.name(), staticResolved.descriptor());
                return;
            }
        }
        if ("class".equals(fieldAccess.fieldName())) {
            String typeName = exprGen.flattenTypeName(fieldAccess.target());
            if (typeName != null) {
                String primitiveWrapper = DescriptorUtils.primitiveClassOwner(typeName);
                if (primitiveWrapper != null) {
                    ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, primitiveWrapper, "TYPE", "Ljava/lang/Class;");
                } else {
                    String internalName = ctx.typeResolver().resolveInternalName(new TypeNode(typeName, null, 0, fieldAccess.line()));
                    ctx.mv().visitLdcInsn(Type.getObjectType(internalName));
                }
                return;
            }
        }

        ResolvedType targetType = ctx.typeInferrer().infer(fieldAccess.target());
        if (targetType != null && targetType.isArray() && "length".equals(fieldAccess.fieldName())) {
            exprGen.generate(fieldAccess.target());
            ctx.mv().visitInsn(Opcodes.ARRAYLENGTH);
            return;
        }
        if (targetType != null && targetType.internalName() != null) {
            MethodResolver.ResolvedField resolved = ctx.methodResolver().resolveField(targetType.internalName(), fieldAccess.fieldName());
            if (resolved != null) {
                if (resolved.isStatic()) {
                    if (exprGen.constantInliner().tryInline(resolved.owner(), resolved.name())) return;
                    ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, resolved.owner(), resolved.name(), resolved.descriptor());
                } else {
                    exprGen.generate(fieldAccess.target());
                    ctx.mv().visitFieldInsn(Opcodes.GETFIELD, targetType.internalName(), resolved.name(), resolved.descriptor());
                }
                return;
            }
            if (ctx.nestedClassFields() != null) {
                Map<String, ResolvedType> fields = ctx.nestedClassFields().get(targetType.internalName());
                if (fields != null && fields.containsKey(fieldAccess.fieldName())) {
                    exprGen.generate(fieldAccess.target());
                    ctx.mv().visitFieldInsn(Opcodes.GETFIELD, targetType.internalName(), fieldAccess.fieldName(), fields.get(fieldAccess.fieldName()).descriptor());
                    return;
                }
            }
        }

        if (fieldAccess.target() instanceof NameExpression nameTarget && ctx.scope().resolve(nameTarget.name()) == null) {
            String ownerInternal = ctx.typeResolver().resolveInternalName(new TypeNode(nameTarget.name(), null, 0, fieldAccess.line()));
            MethodResolver.ResolvedField staticResolved = ctx.methodResolver().resolveField(ownerInternal, fieldAccess.fieldName());
            if (staticResolved != null) {
                if (exprGen.constantInliner().tryInline(staticResolved.owner(), staticResolved.name())) return;
                ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, staticResolved.owner(), staticResolved.name(), staticResolved.descriptor());
                return;
            }
            if (!"I".equals(ownerInternal) && ctx.typeInferrer().inferField(nameTarget.name()) == null && Character.isUpperCase(nameTarget.name().charAt(0))) {
                String desc = expected != null ? expected.descriptor() : "Ljava/lang/Object;";
                ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, ownerInternal, fieldAccess.fieldName(), desc);
                return;
            }
        }

        if (ctx.nestedClassFields() != null) {
            String uniqueOwner = null;
            ResolvedType uniqueType = null;
            for (Map.Entry<String, Map<String, ResolvedType>> e : ctx.nestedClassFields().entrySet()) {
                ResolvedType t = e.getValue().get(fieldAccess.fieldName());
                if (t != null) {
                    if (uniqueOwner != null) {
                        uniqueOwner = null;
                        break;
                    }
                    uniqueOwner = e.getKey();
                    uniqueType = t;
                }
            }
            if (uniqueOwner != null && !uniqueOwner.equals(ctx.classInternalName())) {
                exprGen.generate(fieldAccess.target());
                ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, uniqueOwner);
                ctx.mv().visitFieldInsn(Opcodes.GETFIELD, uniqueOwner, fieldAccess.fieldName(), uniqueType.descriptor());
                return;
            }
        }

        exprGen.generate(fieldAccess.target());
        ResolvedType fallbackType = ctx.typeInferrer().inferField(fieldAccess.fieldName());
        String fallbackDesc = fallbackType != null ? fallbackType.descriptor() : "I";
        ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), fieldAccess.fieldName(), fallbackDesc);
    }
}
