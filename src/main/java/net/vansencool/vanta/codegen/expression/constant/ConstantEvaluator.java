package net.vansencool.vanta.codegen.expression.constant;

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
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.field.reflection.ReflectionFieldSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile time constant evaluation: folds integer/long literal arithmetic
 * chains, resolves {@code static final} field references to their
 * {@code ConstantValue} attribute, and exposes enum ordinals.
 */
public final class ConstantEvaluator {

    private static final Map<String, Boolean> CONSTANT_CACHE = new ConcurrentHashMap<>();

    private final @NotNull ExpressionGenerator exprGen;

    public ConstantEvaluator(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

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
     * Resolves {@code expr} to the compile time value of the static final
     * field it names, when any. Walks supers via the type registry so non
     * public inherited constants still fold.
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
            if (registry().lookup(internal) == null) return null;
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
        FieldSymbol field = findStaticFinalField(owner, fieldName);
        return field != null ? field.constantValue() : null;
    }

    /**
     * @return compile time String value of {@code expr}, or null when not a String constant
     */
    public @Nullable String stringConstant(@NotNull Expression expr) {
        Object v = staticFinalValue(expr);
        return v instanceof String s ? s : null;
    }

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

    public @Nullable Integer intValue(@NotNull Expression expr) {
        MethodContext ctx = exprGen.ctx();
        if (expr instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL) {
            try {
                return LiteralParser.parseIntLiteral(lit.value());
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (expr instanceof UnaryExpression u && u.isPrefix() && ("-".equals(u.operator()) || "+".equals(u.operator()) || "~".equals(u.operator()))) {
            Integer inner = intValue(u.operand());
            if (inner == null) return null;
            return switch (u.operator()) {
                case "-" -> -inner;
                case "~" -> ~inner;
                default -> inner;
            };
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
            if (owner == null && fa.target() instanceof NameExpression typeName && Character.isUpperCase(typeName.name().charAt(0))) {
                String typeInternal = ctx.typeResolver().resolveInternalName(new TypeNode(typeName.name(), null, 0, fa.line()));
                MethodResolver.ResolvedField rf = ctx.methodResolver().resolveField(typeInternal, fa.fieldName());
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
     * Looks up the {@code ConstantValue} attribute for a static final field
     * without emitting any instruction. Honors the per batch
     * {@code nestedClassConstants} cache when present.
     */
    public @Nullable Object constantValue(@NotNull String ownerInternal, @NotNull String fieldName) {
        MethodContext ctx = exprGen.ctx();
        if (ctx.nestedClassConstants() != null) {
            Map<String, Object> consts = ctx.nestedClassConstants().get(ownerInternal);
            if (consts != null && consts.containsKey(fieldName)) return consts.get(fieldName);
        }
        FieldSymbol field = findStaticFinalField(ownerInternal, fieldName);
        if (field == null) return null;
        TypeRef type = field.type();
        if (!type.isPrimitive() && !"java/lang/String".equals(type.internalName())) return null;
        if (field instanceof ReflectionFieldSymbol reflectionField) {
            Class<?> declaring = reflectionField.reflective().getDeclaringClass();
            if (!hasConstantValueAttribute(declaring, fieldName)) return null;
        }
        return field.constantValue();
    }

    /**
     * Checks whether a class file actually stores a {@code ConstantValue}
     * attribute for a field. Needed because reflection returns the runtime
     * value even for non inlined static finals, but javac only inlines
     * those that carry this attribute.
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

    public @Nullable Integer enumOrdinalFor(@NotNull String enumInternal, @NotNull String constantName) {
        TypeSymbol sym = registry().lookup(enumInternal);
        if (sym == null || !sym.isEnum()) return null;
        int ord = 0;
        for (FieldSymbol f : sym.fields()) {
            if ((f.access() & Opcodes.ACC_ENUM) == 0) continue;
            if (f.name().equals(constantName)) return ord;
            ord++;
        }
        return null;
    }

    private @Nullable FieldSymbol findStaticFinalField(@NotNull String ownerInternal, @NotNull String fieldName) {
        TypeSymbol owner = registry().lookup(ownerInternal);
        if (owner == null) return null;
        return findStaticFinalField(owner, fieldName);
    }

    private @Nullable FieldSymbol findStaticFinalField(@NotNull TypeSymbol owner, @NotNull String fieldName) {
        for (FieldSymbol f : owner.fields()) {
            if (!f.name().equals(fieldName)) continue;
            if (!f.isStatic() || !f.isFinal()) return null;
            return f;
        }
        for (TypeSymbol iface : owner.interfaces()) {
            FieldSymbol found = findStaticFinalField(iface, fieldName);
            if (found != null) return found;
        }
        TypeSymbol sup = owner.superclass();
        return sup != null ? findStaticFinalField(sup, fieldName) : null;
    }

    private @NotNull TypeRegistry registry() {
        return exprGen.ctx().methodResolver().classpathManager().typeRegistry();
    }
}
