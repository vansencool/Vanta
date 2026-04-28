package net.vansencool.vanta.codegen.expression.array;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Emits bytecode for array-shaped expressions: element access, {@code new T[n]}
 * with explicit dimensions, {@code new T[]{...}} initializer forms, and bare
 * {@code {...}} initializer literals that take their element type from the
 * surrounding assignment or call-site context.
 */
public final class ArrayEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for index/element
     *                emission and widen/box helpers
     */
    public ArrayEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Picks the {@code xALOAD} opcode matching a single-char element
     * descriptor.
     *
     * @param elementDesc JVM descriptor of the array element
     * @return matching array-load opcode
     */
    private static int elementLoadOpcode(@NotNull String elementDesc) {
        return switch (elementDesc) {
            case "B", "Z" -> Opcodes.BALOAD;
            case "C" -> Opcodes.CALOAD;
            case "S" -> Opcodes.SALOAD;
            case "I" -> Opcodes.IALOAD;
            case "J" -> Opcodes.LALOAD;
            case "F" -> Opcodes.FALOAD;
            case "D" -> Opcodes.DALOAD;
            default -> Opcodes.AALOAD;
        };
    }

    /**
     * @param type primitive resolved type
     * @return the {@code T_*} constant used in {@code NEWARRAY} for that type
     */
    public static int primitiveArrayType(@NotNull ResolvedType type) {
        if (type.equals(ResolvedType.BOOLEAN)) return Opcodes.T_BOOLEAN;
        if (type.equals(ResolvedType.BYTE)) return Opcodes.T_BYTE;
        if (type.equals(ResolvedType.SHORT)) return Opcodes.T_SHORT;
        if (type.equals(ResolvedType.CHAR)) return Opcodes.T_CHAR;
        if (type.equals(ResolvedType.INT)) return Opcodes.T_INT;
        if (type.equals(ResolvedType.LONG)) return Opcodes.T_LONG;
        if (type.equals(ResolvedType.FLOAT)) return Opcodes.T_FLOAT;
        if (type.equals(ResolvedType.DOUBLE)) return Opcodes.T_DOUBLE;
        return Opcodes.T_INT;
    }

    /**
     * Emits an {@code array[index]} load, picking the right {@code xALOAD}
     * opcode from the inferred element type.
     *
     * @param arrayAccess array-access node
     */
    public void emitArrayAccess(@NotNull ArrayAccessExpression arrayAccess) {
        MethodContext ctx = exprGen.ctx();
        exprGen.generate(arrayAccess.array());
        exprGen.generate(arrayAccess.index());
        ResolvedType arrayType = ctx.typeInferrer().infer(arrayAccess.array());
        if (arrayType != null && arrayType.descriptor().startsWith("[")) {
            String elementDesc = arrayType.descriptor().substring(1);
            ctx.mv().visitInsn(elementLoadOpcode(elementDesc));
        } else {
            ctx.mv().visitInsn(Opcodes.IALOAD);
        }
    }

    /**
     * Emits a {@code new T[...]} expression. Handles the initializer-only
     * form ({@code new int[]{1,2,3}}), explicit single-dimension form, and
     * {@code MULTIANEWARRAY} for multi-dim allocations.
     *
     * @param newArray new-array node
     */
    public void emitNewArray(@NotNull NewArrayExpression newArray) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();

        ResolvedType elementType = ctx.typeResolver().resolve(newArray.elementType());
        int totalDims = newArray.dimensionExpressions().size() + newArray.extraDimensions();

        String elementInternal = elementType.internalName() != null ? elementType.internalName() : "java/lang/Object";
        if (newArray.initializer() != null && newArray.dimensionExpressions().isEmpty()) {
            OpcodeUtils.pushInt(mv, newArray.initializer().elements().size());
            if (elementType.isPrimitive() && totalDims == 1) {
                mv.visitIntInsn(Opcodes.NEWARRAY, primitiveArrayType(elementType));
            } else if (totalDims == 1) {
                mv.visitTypeInsn(Opcodes.ANEWARRAY, elementInternal);
            } else {
                String arrayDesc = elementType.asArray(totalDims - 1).descriptor();
                mv.visitTypeInsn(Opcodes.ANEWARRAY, arrayDesc);
            }
            emitInitElements(newArray.initializer().elements(), totalDims > 1 ? elementType.asArray(totalDims - 1) : elementType);
            return;
        }

        for (Expression dimExpr : newArray.dimensionExpressions()) {
            exprGen.generate(dimExpr);
        }

        if (newArray.dimensionExpressions().size() == 1 && totalDims == 1) {
            if (elementType.isPrimitive()) {
                int atype = primitiveArrayType(elementType);
                mv.visitIntInsn(Opcodes.NEWARRAY, atype);
            } else {
                mv.visitTypeInsn(Opcodes.ANEWARRAY, elementInternal);
            }
        } else if (newArray.dimensionExpressions().size() == 1 && totalDims > 1) {
            String componentDesc = elementType.asArray(totalDims - 1).descriptor();
            mv.visitTypeInsn(Opcodes.ANEWARRAY, componentDesc);
        } else if (newArray.dimensionExpressions().size() > 1) {
            String arrayDesc = elementType.asArray(totalDims).descriptor();
            mv.visitMultiANewArrayInsn(arrayDesc, newArray.dimensionExpressions().size());
        }

        if (newArray.initializer() != null) {
            emitInitElements(newArray.initializer().elements(), elementType);
        }
    }

    /**
     * Emits a bare {@code {...}} array initializer. Takes the expected array
     * type from context so nested initializers pick the right element type
     * instead of defaulting to {@code int[]}.
     *
     * @param arrayInit initializer node
     * @param expected  expected array type from assignment/call-site context, or null
     */
    public void emitArrayInitializer(@NotNull ArrayInitializerExpression arrayInit, @Nullable ResolvedType expected) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ResolvedType arrayType = expected;
        if (arrayType == null || !arrayType.descriptor().startsWith("[")) {
            arrayType = ResolvedType.INT.asArray(1);
        }
        String arrayDesc = arrayType.descriptor();
        String elementDesc = arrayDesc.substring(1);
        ResolvedType elementType = ResolvedType.fromDescriptor(elementDesc);
        OpcodeUtils.pushInt(mv, arrayInit.elements().size());
        if (elementDesc.startsWith("[") || elementDesc.startsWith("L")) {
            String ref = elementDesc.startsWith("L") ? elementDesc.substring(1, elementDesc.length() - 1) : elementDesc;
            mv.visitTypeInsn(Opcodes.ANEWARRAY, ref);
        } else {
            mv.visitIntInsn(Opcodes.NEWARRAY, primitiveArrayType(elementType));
        }
        emitInitElements(arrayInit.elements(), elementType);
    }

    /**
     * Emits the per-element store loop shared by {@link #emitNewArray} and
     * {@link #emitArrayInitializer}. Handles box/unbox/widen coercions so
     * each element matches the array's declared component type.
     *
     * @param elements    initializer elements
     * @param elementType resolved element type
     */
    public void emitInitElements(@NotNull List<Expression> elements, @NotNull ResolvedType elementType) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        for (int i = 0; i < elements.size(); i++) {
            mv.visitInsn(Opcodes.DUP);
            OpcodeUtils.pushInt(mv, i);
            exprGen.generate(elements.get(i), elementType);
            ResolvedType actual = ctx.typeInferrer().infer(elements.get(i));
            if (actual != null && actual.isPrimitive() && elementType.isPrimitive() && !actual.descriptor().equals(elementType.descriptor())) {
                exprGen.numericCoercion().widen(actual, elementType.descriptor());
            } else if (elementType.isPrimitive() && actual != null && !actual.isPrimitive()) {
                String wrapper = exprGen.numericCoercion().wrapperInternalName(elementType.descriptor());
                if (wrapper != null) exprGen.unboxingEmitter().withCast(mv, elementType.descriptor(), wrapper);
            } else if (!elementType.isPrimitive() && actual != null && actual.isPrimitive()) {
                PrimitiveConversionEmitter.emitBoxing(mv, actual);
            }
            mv.visitInsn(OpcodeUtils.arrayStoreOpcode(elementType));
        }
    }

    /**
     * Picks the {@code xALOAD} opcode for an {@link ArrayAccessExpression}
     * based on the inferred element type of its array receiver.
     *
     * @param arrayAccess array-access whose load opcode is needed
     * @return matching {@code xALOAD} opcode, defaulting to {@code AALOAD}
     */
    public int arrayLoadOpcodeFor(@NotNull ArrayAccessExpression arrayAccess) {
        ResolvedType arrayType = exprGen.ctx().typeInferrer().infer(arrayAccess.array());
        if (arrayType != null && arrayType.descriptor().startsWith("[")) {
            return elementLoadOpcode(arrayType.descriptor().substring(1));
        }
        return Opcodes.AALOAD;
    }

    /**
     * Store counterpart to {@link #arrayLoadOpcodeFor(ArrayAccessExpression)}.
     *
     * @param arrayAccess array-access whose store opcode is needed
     * @return matching {@code xASTORE} opcode, defaulting to {@code IASTORE}
     * when the receiver type cannot be inferred
     */
    public int arrayStoreOpcodeFor(@NotNull ArrayAccessExpression arrayAccess) {
        ResolvedType arrayType = exprGen.ctx().typeInferrer().infer(arrayAccess.array());
        if (arrayType != null && arrayType.descriptor().startsWith("[")) {
            String elementDesc = arrayType.descriptor().substring(1);
            return switch (elementDesc) {
                case "B", "Z" -> Opcodes.BASTORE;
                case "C" -> Opcodes.CASTORE;
                case "S" -> Opcodes.SASTORE;
                case "I" -> Opcodes.IASTORE;
                case "J" -> Opcodes.LASTORE;
                case "F" -> Opcodes.FASTORE;
                case "D" -> Opcodes.DASTORE;
                default -> Opcodes.AASTORE;
            };
        }
        return Opcodes.IASTORE;
    }
}
