package net.vansencool.vanta.codegen.expression.constant;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.FieldInfo;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile-time constant evaluation: folds integer/long literal arithmetic
 * chains, resolves {@code static final} field references to their
 * {@code ConstantValue} attribute, and exposes enum ordinals. Used so the
 * emitter can replace constant-valued subtrees with a single push instruction
 * rather than emitting a real field access at runtime.
 */
public final class ConstantEvaluator {

    private static final Map<String, Boolean> CONSTANT_CACHE = new ConcurrentHashMap<>();

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator providing context and
     *                parenthesis-unwrapping
     */
    public ConstantEvaluator(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Walks a class and its superclasses looking for a {@code static final}
     * field of primitive or {@code String} type. Uses {@link Class#getDeclaredField}
     * so package-private / private constants on {@code this} class (that
     * {@link Class#getField} would filter out) still participate in folding.
     *
     * @param c    starting class
     * @param name field name to search for
     * @return matching field declaration, or null if no eligible field exists
     * on the inheritance chain
     */
    public static @Nullable Field findStaticFinalField(@NotNull Class<?> c, @NotNull String name) {
        for (Class<?> walk = c; walk != null; walk = walk.getSuperclass()) {
            try {
                Field f = walk.getDeclaredField(name);
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods)) continue;
                if (!Modifier.isFinal(mods)) continue;
                if (!f.getType().isPrimitive() && !String.class.equals(f.getType())) continue;
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * Folds a binary expression whose operands are both integer/long constants
     * into a single value, mirroring javac's constant-expression evaluation.
     *
     * @param binary binary expression node
     * @return folded value, or {@code null} when either operand is not a
     * fold-eligible literal or the operator cannot be safely folded
     * (e.g. integer division by zero)
     */
    public @Nullable Long foldLong(@NotNull BinaryExpression binary) {
        Long lv = longValue(binary.left());
        Long rv = longValue(binary.right());
        if (lv == null || rv == null) return null;
        long l = lv;
        long r = rv;
        return switch (binary.operator()) {
            case "+" -> l + r;
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> r == 0 ? null : l / r;
            case "%" -> r == 0 ? null : l % r;
            case "&" -> l & r;
            case "|" -> l | r;
            case "^" -> l ^ r;
            case "<<" -> l << r;
            case ">>" -> l >> r;
            case ">>>" -> l >>> r;
            default -> null;
        };
    }

    /**
     * @param expr expression node
     * @return literal value as {@code Long} when {@code expr} reduces to an
     * integer/long/char constant (possibly through parentheses, unary
     * negation, nested folds, or a static-final reference), else null
     */
    public @Nullable Long longValue(@NotNull Expression expr) {
        Expression cur = exprGen.unwrapParens(expr);
        if (cur instanceof LiteralExpression lit) {
            return switch (lit.literalType()) {
                case INT_LITERAL -> (long) LiteralParser.parseIntLiteral(lit.value());
                case LONG_LITERAL -> LiteralParser.parseLongLiteral(lit.value());
                case CHAR_LITERAL -> (long) LiteralParser.parseCharLiteral(lit.value());
                default -> null;
            };
        }
        if (cur instanceof UnaryExpression u && u.isPrefix()) {
            switch (u.operator()) {
                case "-" -> {
                    Long inner = longValue(u.operand());
                    if (inner != null) return -inner;
                }
                case "+" -> {
                    return longValue(u.operand());
                }
                case "~" -> {
                    Long inner = longValue(u.operand());
                    if (inner != null) return ~inner;
                }
            }
        }
        if (cur instanceof BinaryExpression bin) return foldLong(bin);
        Object fieldConst = staticFinalValue(cur);
        if (fieldConst instanceof Integer i) return (long) i;
        if (fieldConst instanceof Long l) return l;
        if (fieldConst instanceof Character c) return (long) c;
        if (fieldConst instanceof Short s) return (long) s;
        if (fieldConst instanceof Byte b) return (long) b;
        return null;
    }

    /**
     * Resolves {@code expr} to the compile-time value of the static-final
     * field it names, if any. Walks superclasses so non-public inherited
     * constants still fold.
     *
     * @param expr expression node, expected to be a bare name or
     *             {@code Type.FIELD} access
     * @return the field's constant value, or null when {@code expr} does not
     * resolve to a static-final field of primitive or {@code String}
     * type
     */
    public @Nullable Object staticFinalValue(@NotNull Expression expr) {
        MethodContext ctx = exprGen.ctx();
        String owner;
        String fieldName;
        if (expr instanceof FieldAccessExpression fa && fa.target() instanceof NameExpression tgt) {
            if (ctx.scope().resolve(tgt.name()) != null) return null;
            if (ctx.typeInferrer().inferField(tgt.name()) != null) return null;
            if (!Character.isUpperCase(tgt.name().charAt(0))) return null;
            String internal = ctx.typeResolver().resolveInternalName(new TypeNode(tgt.name(), null, 0, expr.line()));
            if (!ctx.methodResolver().classpathManager().exists(internal)) return null;
            owner = internal;
            fieldName = fa.fieldName();
        } else if (expr instanceof NameExpression ne) {
            if (ctx.scope().resolve(ne.name()) != null) return null;
            String staticOwner = ctx.typeResolver().resolveStaticFieldOwner(ne.name());
            if (staticOwner != null) {
                owner = staticOwner;
            } else if (ctx.typeInferrer().inferField(ne.name()) != null) {
                owner = ctx.classInternalName();
            } else {
                return null;
            }
            fieldName = ne.name();
        } else {
            return null;
        }
        Class<?> c = ctx.methodResolver().classpathManager().loadClass(owner);
        if (c != null) {
            Field f = findStaticFinalField(c, fieldName);
            if (f != null) {
                try {
                    f.setAccessible(true);
                    return f.get(null);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                }
            }
        }
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(owner);
        if (info != null) {
            for (FieldInfo fi : info.fields()) {
                if (!fi.name().equals(fieldName)) continue;
                int acc = fi.access();
                if (!Modifier.isStatic(acc) || !Modifier.isFinal(acc)) continue;
                Object v = fi.constantValue();
                if (v != null) return v;
            }
        }
        return null;
    }

    /**
     * Folds a constant int value from shallow expressions (literal, unary
     * minus, parenthesized) without touching the classpath.
     *
     * @param value expression node
     * @return folded int, or null when {@code value} is not a shape-local int
     * constant
     */
    public @Nullable Integer simpleIntValue(@NotNull Expression value) {
        Expression e = value;
        while (e instanceof ParenExpression p) e = p.expression();
        boolean negate = false;
        if (e instanceof UnaryExpression u && "-".equals(u.operator()) && u.isPrefix()) {
            negate = true;
            e = u.operand();
        }
        if (!(e instanceof LiteralExpression lit) || lit.literalType() != TokenType.INT_LITERAL) return null;
        try {
            int n = LiteralParser.parseIntLiteral(lit.value());
            return negate ? -n : n;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * @param expr expression node
     * @return compile-time int value when {@code expr} is an int literal or a
     * reference to a static-final int/byte/short/char field, else null
     */
    public @Nullable Integer intValue(@NotNull Expression expr) {
        MethodContext ctx = exprGen.ctx();
        if (expr instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL) {
            try {
                return LiteralParser.parseIntLiteral(lit.value());
            } catch (RuntimeException e) {
                return null;
            }
        }
        String owner = null;
        String field = null;
        if (expr instanceof NameExpression ne) {
            LocalVariable local = ctx.scope().resolve(ne.name());
            if (local != null) return null;
            MethodResolver.ResolvedField rf = ctx.methodResolver().resolveField(ctx.classInternalName(), ne.name());
            if (rf != null && rf.isStatic()) {
                owner = rf.owner();
                field = rf.name();
            } else {
                String staticOwner = ctx.typeResolver().resolveStaticFieldOwner(ne.name());
                if (staticOwner != null) {
                    MethodResolver.ResolvedField sr = ctx.methodResolver().resolveField(staticOwner, ne.name());
                    if (sr != null && sr.isStatic()) {
                        owner = sr.owner();
                        field = sr.name();
                    }
                }
                if (owner == null) {
                    String encl = exprGen.objectCreationEmitter().enclosingOuterFor(ctx.classInternalName());
                    while (encl != null) {
                        MethodResolver.ResolvedField er = ctx.methodResolver().resolveField(encl, ne.name());
                        if (er != null && er.isStatic()) {
                            owner = er.owner();
                            field = er.name();
                            break;
                        }
                        encl = exprGen.objectCreationEmitter().enclosingOuterFor(encl);
                    }
                }
            }
        } else if (expr instanceof FieldAccessExpression fa) {
            ResolvedType tt = ctx.typeInferrer().infer(fa.target());
            if (tt != null && tt.internalName() != null) {
                MethodResolver.ResolvedField rf = ctx.methodResolver().resolveField(tt.internalName(), fa.fieldName());
                if (rf != null && rf.isStatic()) {
                    owner = rf.owner();
                    field = rf.name();
                }
            }
        }
        if (owner == null) return null;
        Object v = constantValue(owner, field);
        if (v instanceof Integer i) return i;
        if (v instanceof Byte b) return b.intValue();
        if (v instanceof Short s) return s.intValue();
        if (v instanceof Character c) return (int) c;
        return null;
    }

    /**
     * Looks up the {@code ConstantValue} attribute for a static-final field
     * without emitting any instruction.
     *
     * @param ownerInternal owner class internal name
     * @param fieldName     field name
     * @return the stored constant value, or null if the field is missing,
     * non-constant, or the owner cannot be loaded
     */
    public @Nullable Object constantValue(@NotNull String ownerInternal, @NotNull String fieldName) {
        MethodContext ctx = exprGen.ctx();
        if (ctx.nestedClassConstants() != null) {
            Map<String, Object> consts = ctx.nestedClassConstants().get(ownerInternal);
            if (consts != null && consts.containsKey(fieldName)) return consts.get(fieldName);
        }
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
                if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) return null;
                Class<?> ftype = f.getType();
                if (!ftype.isPrimitive() && ftype != String.class) return null;
                if (!hasConstantValueAttribute(clazz, fieldName)) return null;
                return f.get(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(ownerInternal);
        if (info == null) return null;
        for (FieldInfo fi : info.fields()) {
            if (!fi.name().equals(fieldName)) continue;
            if (!fi.isStatic()) return null;
            if ((fi.access() & Opcodes.ACC_FINAL) == 0) return null;
            return fi.constantValue();
        }
        return null;
    }

    /**
     * Checks whether a class file actually stores a {@code ConstantValue}
     * attribute for a field. Needed because {@link Field#get} returns the
     * runtime value even for non-inlined static-finals, but javac only inlines
     * those that carry this attribute.
     *
     * @param clazz     owner class
     * @param fieldName field name
     * @return true when the class file carries a {@code ConstantValue}
     * attribute for that field
     */
    public boolean hasConstantValueAttribute(@NotNull Class<?> clazz, @NotNull String fieldName) {
        String key = clazz.getName() + "#" + fieldName;
        Boolean cached = CONSTANT_CACHE.get(key);
        if (cached != null) return cached;
        boolean result = false;
        try (InputStream is = clazz.getResourceAsStream("/" + clazz.getName().replace('.', '/') + ".class")) {
            if (is != null) {
                ClassReader cr = new ClassReader(is);
                final boolean[] found = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                        if (name.equals(fieldName) && value != null) found[0] = true;
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                result = found[0];
            }
        } catch (IOException ignored) {
        }
        CONSTANT_CACHE.put(key, result);
        return result;
    }

    /**
     * @param enumInternal enum class internal name
     * @param constantName declared enum constant name
     * @return ordinal of the matching constant, or null when the class cannot
     * be resolved as an enum or the constant is not present
     */
    public @Nullable Integer enumOrdinalFor(@NotNull String enumInternal, @NotNull String constantName) {
        MethodContext ctx = exprGen.ctx();
        Class<?> c = ctx.methodResolver().classpathManager().loadClass(enumInternal);
        if (c != null && c.isEnum()) {
            try {
                Object[] consts = c.getEnumConstants();
                if (consts != null) {
                    for (int i = 0; i < consts.length; i++) {
                        if (((Enum<?>) consts[i]).name().equals(constantName)) return i;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(enumInternal);
        if (info != null) {
            int ord = 0;
            for (FieldInfo f : info.fields()) {
                if ((f.access() & Opcodes.ACC_ENUM) == 0) continue;
                if (f.name().equals(constantName)) return ord;
                ord++;
            }
        }
        return null;
    }
}
