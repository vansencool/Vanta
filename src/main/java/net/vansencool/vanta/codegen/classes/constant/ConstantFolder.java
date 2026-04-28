package net.vansencool.vanta.codegen.classes.constant;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.FieldInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Evaluates field initializers at compile time, producing values suitable for
 * the {@code ConstantValue} attribute (JVMS &#xA7;4.7.2). Walks literal, unary,
 * binary, cast, paren, and static-final name/field-access expressions, folding
 * each node when all operands are themselves foldable. Mirrors the subset of
 * javac's constant-folding pass needed to emit matching {@code ConstantValue}
 * attributes on {@code static final} fields.
 */
public final class ConstantFolder {

    private final @NotNull TypeResolver typeResolver;
    private final @NotNull ClasspathManager classpathManager;
    private @Nullable ClassDeclaration currentClassDecl;
    private @Nullable TypeKind currentOwnerKind;

    /**
     * Creates a folder bound to the surrounding resolver and classpath. The
     * caller drives the current-class context via
     * {@link #currentClassDecl(ClassDeclaration)} and
     * {@link #currentOwnerKind(TypeKind)} as each type's bytecode is generated.
     *
     * @param typeResolver     type resolver for looking up static-import owners
     * @param classpathManager classpath manager for reading external constants
     */
    public ConstantFolder(@NotNull TypeResolver typeResolver, @NotNull ClasspathManager classpathManager) {
        this.typeResolver = typeResolver;
        this.classpathManager = classpathManager;
    }

    /**
     * Sets the class declaration whose sibling fields are in scope for
     * self-referential folding (e.g. {@code int X = OTHER_CONST}).
     *
     * @param decl declaration currently being generated, or null when between classes
     */
    public void currentClassDecl(@Nullable ClassDeclaration decl) {
        this.currentClassDecl = decl;
    }

    /**
     * Sets the kind of the class being generated so interface-like declarations
     * get implicit {@code static final public} field modifiers applied during
     * constant resolution.
     *
     * @param kind type kind of the class currently being generated
     */
    public void currentOwnerKind(@Nullable TypeKind kind) {
        this.currentOwnerKind = kind;
    }

    /**
     * Folds a field's initializer into a JVM constant-pool value when the
     * field is a {@code ConstantValue}-eligible {@code static final}.
     *
     * @param fieldDecl  enclosing field declaration carrying modifiers and type
     * @param declarator specific declarator within {@code fieldDecl}
     * @return folded value boxed as {@link Integer}/{@link Long}/{@link Float}/{@link Double}/{@link String},
     * or null when folding is not possible or the field is not eligible
     */
    public @Nullable Object resolveConstantValue(@NotNull FieldDeclaration fieldDecl, @NotNull FieldDeclarator declarator) {
        if (declarator.initializer() == null) return null;
        int mods = fieldDecl.modifiers();
        boolean inInterfaceLike = currentOwnerKind == TypeKind.INTERFACE || currentOwnerKind == TypeKind.ANNOTATION;
        if (inInterfaceLike) mods |= Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_PUBLIC;
        if ((mods & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
            return null;
        if (fieldDecl.type().arrayDimensions() > 0) return null;
        String fieldTypeName = fieldDecl.type().name();
        if (!isConstantValueEligibleType(fieldTypeName)) return null;
        Object v = foldConstExpr(declarator.initializer());
        if (v == null) return null;
        return coerceConstForField(v, fieldTypeName);
    }

    /**
     * True when a field of {@code typeName} can legally carry a
     * {@code ConstantValue} attribute per JVMS &#xA7;4.7.2. Boxed wrappers
     * (e.g. {@link Integer}) cannot: their initializers must run in
     * {@code <clinit>}.
     *
     * @param typeName source-level type name (primitives or {@code "String"})
     * @return true when the type is ConstantValue-eligible
     */
    public boolean isConstantValueEligibleType(@NotNull String typeName) {
        return switch (typeName) {
            case "byte", "short", "char", "int", "long", "float", "double", "boolean", "String" -> true;
            default -> false;
        };
    }

    /**
     * Recursively folds {@code expr} into a compile-time constant value when
     * possible, returning the boxed result or null when any subexpression is
     * non-constant.
     *
     * @param expr expression to fold, nullable for convenience at field-init sites
     * @return folded constant or null when folding is not possible
     */
    public @Nullable Object foldConstExpr(@Nullable Expression expr) {
        if (expr == null) return null;
        if (expr instanceof ParenExpression p) return foldConstExpr(p.expression());
        if (expr instanceof LiteralExpression lit) return foldLiteralToConstant(lit);
        if (expr instanceof UnaryExpression u) {
            Object v = foldConstExpr(u.operand());
            if (v == null) return null;
            return applyUnary(u.operator(), v);
        }
        if (expr instanceof BinaryExpression b) {
            Object l = foldConstExpr(b.left());
            if (l == null) return null;
            Object r = foldConstExpr(b.right());
            if (r == null) return null;
            return applyBinary(b.operator(), l, r);
        }
        if (expr instanceof CastExpression c) {
            Object v = foldConstExpr(c.expression());
            if (v == null) return null;
            String name = c.type().name();
            return coerceConstForField(v, name);
        }
        if (expr instanceof NameExpression ne) {
            Object v = readConstFieldValueFromDecl(ne.name());
            if (v != null) return v;
            return readConstFieldValue(null, ne.name());
        }
        if (expr instanceof FieldAccessExpression fa
                && fa.target() instanceof NameExpression owner) {
            String ownerInternal = typeResolver.resolveInternalName(new TypeNode(owner.name(), null, 0, fa.line()));
            if (!"I".equals(ownerInternal)) {
                return readConstFieldValue(ownerInternal, fa.fieldName());
            }
        }
        return null;
    }

    /**
     * Looks up a field by name among the fields declared directly on
     * {@link #currentClassDecl(ClassDeclaration) the current class},
     * returning its folded initializer when it's a {@code static final}
     * compile-time constant. Lets self-referential initializers like
     * {@code int SOURCE_MASK = SOURCE_DEPRECATED} fold in a single pass.
     *
     * @param fieldName sibling-field name to resolve
     * @return folded constant when the sibling is a foldable static final, else null
     */
    private @Nullable Object readConstFieldValueFromDecl(@NotNull String fieldName) {
        ClassDeclaration decl = currentClassDecl;
        if (decl == null) return null;
        for (AstNode m : decl.members()) {
            if (!(m instanceof FieldDeclaration fd)) continue;
            for (FieldDeclarator d : fd.declarators()) {
                if (!d.name().equals(fieldName)) continue;
                int mods = fd.modifiers();
                boolean inInterfaceLike = currentOwnerKind == TypeKind.INTERFACE || currentOwnerKind == TypeKind.ANNOTATION;
                if (inInterfaceLike) mods |= Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                if ((mods & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                    return null;
                if (fd.type().arrayDimensions() > 0) return null;
                Object v = foldConstExpr(d.initializer());
                if (v == null) return null;
                return coerceConstForField(v, fd.type().name());
            }
        }
        return null;
    }

    /**
     * Reads an external {@code static final} field's compile-time constant
     * value via reflection, falling back to ASM {@code ConstantValue}
     * attributes when the owning class cannot be loaded.
     *
     * @param ownerInternal owning class's internal name, or null to resolve
     *                      through static imports in the current type resolver
     * @param fieldName     simple field name
     * @return folded constant or null when the field is not a foldable static final
     */
    private @Nullable Object readConstFieldValue(@Nullable String ownerInternal, @NotNull String fieldName) {
        if (ownerInternal == null) ownerInternal = typeResolver.resolveStaticFieldOwner(fieldName);
        if (ownerInternal == null) return null;
        Class<?> cls = classpathManager.loadClass(ownerInternal);
        if (cls != null) {
            Field f = ClasspathManager.safeGetField(cls, fieldName);
            if (f != null && Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                try {
                    Object v = f.get(null);
                    if (v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof Double || v instanceof String)
                        return v;
                    if (v instanceof Character c) return (int) c;
                    if (v instanceof Short s) return (int) s;
                    if (v instanceof Byte bt) return (int) bt;
                    if (v instanceof Boolean bl) return bl ? 1 : 0;
                } catch (Throwable ignored) {
                }
            }
        }
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info != null) {
            for (FieldInfo fi : info.fields()) {
                if (!fi.name().equals(fieldName)) continue;
                int acc = fi.access();
                if (!Modifier.isStatic(acc) || !Modifier.isFinal(acc)) continue;
                Object v = fi.constantValue();
                if (v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof Double || v instanceof String)
                    return v;
            }
        }
        return null;
    }

    /**
     * Applies a Java unary operator to an already-folded operand. Returns null
     * for operators that don't map to a compile-time constant (e.g. {@code ++},
     * {@code --}) or for operand types outside the supported numeric set.
     *
     * @param op single-char operator from source
     * @param v  folded operand value
     * @return folded result or null when the operator/operand pair is not supported
     */
    private @Nullable Object applyUnary(@NotNull String op, @NotNull Object v) {
        switch (op) {
            case "+" -> {
                return v;
            }
            case "-" -> {
                if (v instanceof Integer i) return -i;
                if (v instanceof Long l) return -l;
                if (v instanceof Float f) return -f;
                if (v instanceof Double d) return -d;
                return null;
            }
            case "~" -> {
                if (v instanceof Integer i) return ~i;
                if (v instanceof Long l) return ~l;
                return null;
            }
            case "!" -> {
                if (v instanceof Integer i) return i == 0 ? 1 : 0;
                return null;
            }
        }
        return null;
    }

    /**
     * Applies a Java binary operator to two already-folded operands, performing
     * JLS-style numeric promotion (widening smaller types to match the larger).
     *
     * @param op operator token from source
     * @param l  left operand value
     * @param r  right operand value
     * @return folded result boxed in its widened type, or null for non-foldable shapes
     * (e.g. {@code String} with non-{@code +}, division by zero)
     */
    private @Nullable Object applyBinary(@NotNull String op, @NotNull Object l, @NotNull Object r) {
        if (l instanceof String || r instanceof String) {
            if ("+".equals(op)) return String.valueOf(l) + r;
            return null;
        }
        if (!(l instanceof Number) || !(r instanceof Number)) return null;
        if (l instanceof Double || r instanceof Double) {
            double a = ((Number) l).doubleValue();
            double b = ((Number) r).doubleValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? null : a / b;
                case "%" -> b == 0 ? null : a % b;
                default -> null;
            };
        }
        if (l instanceof Float || r instanceof Float) {
            float a = ((Number) l).floatValue();
            float b = ((Number) r).floatValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0f ? null : a / b;
                case "%" -> b == 0f ? null : a % b;
                default -> null;
            };
        }
        if (l instanceof Long || r instanceof Long) {
            long a = ((Number) l).longValue();
            long b = ((Number) r).longValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0L ? null : a / b;
                case "%" -> b == 0L ? null : a % b;
                case "&" -> a & b;
                case "|" -> a | b;
                case "^" -> a ^ b;
                case "<<" -> a << b;
                case ">>" -> a >> b;
                case ">>>" -> a >>> b;
                default -> null;
            };
        }
        int a = ((Number) l).intValue();
        int b = ((Number) r).intValue();
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? null : a / b;
            case "%" -> b == 0 ? null : a % b;
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            case "<<" -> a << b;
            case ">>" -> a >> b;
            case ">>>" -> a >>> b;
            default -> null;
        };
    }

    /**
     * Coerces a folded value into the boxed form matching the declared field
     * type so the emitted {@code ConstantValue} attribute carries the right
     * runtime kind (e.g. narrowing int-shaped folds to byte/short/char).
     *
     * @param v        already-folded value
     * @param typeName source-level target type name
     * @return value rebased into the target type's box, or null for unrepresentable combinations
     */
    public @Nullable Object coerceConstForField(@NotNull Object v, @NotNull String typeName) {
        return switch (typeName) {
            case "byte" -> v instanceof Number n ? (int) (byte) n.intValue() : null;
            case "short" -> v instanceof Number n ? (int) (short) n.intValue() : null;
            case "char" -> v instanceof Number n ? (n.intValue() & 0xFFFF) : null;
            case "int", "boolean" -> v instanceof Number n ? n.intValue() : v;
            case "long" -> v instanceof Number n ? n.longValue() : null;
            case "float" -> v instanceof Number n ? n.floatValue() : null;
            case "double" -> v instanceof Number n ? n.doubleValue() : null;
            case "String" -> v instanceof String ? v : null;
            default -> v;
        };
    }

    /**
     * Converts a literal token to the matching constant-pool value, or null
     * for literal kinds ({@code null}, etc.) that have no {@code ConstantValue}
     * representation.
     *
     * @param lit literal expression straight from the parser
     * @return boxed {@link Integer}/{@link Long}/{@link Float}/{@link Double}/{@link String},
     * or null when the literal kind cannot be held in a {@code ConstantValue}
     */
    public @Nullable Object foldLiteralToConstant(@NotNull LiteralExpression lit) {
        return switch (lit.literalType()) {
            case INT_LITERAL -> LiteralParser.parseIntLiteral(lit.value());
            case LONG_LITERAL -> LiteralParser.parseLongLiteral(lit.value());
            case FLOAT_LITERAL -> Float.parseFloat(lit.value().replace("_", "").replace("f", "").replace("F", ""));
            case DOUBLE_LITERAL -> Double.parseDouble(lit.value().replace("_", "").replace("d", "").replace("D", ""));
            case TRUE -> 1;
            case FALSE -> 0;
            case CHAR_LITERAL -> {
                String v = lit.value();
                if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'')
                    v = v.substring(1, v.length() - 1);
                yield v.isEmpty() ? 0 : (int) v.charAt(0);
            }
            case STRING_LITERAL, TEXT_BLOCK -> unescapeStringLiteral(lit.value());
            default -> null;
        };
    }

    /**
     * Strips the surrounding quote delimiters from a raw string-literal token
     * value (handling both regular {@code "..."} and triple-quoted text-block
     * forms) and decodes the standard backslash escape sequences into their
     * runtime characters.
     */
    private @NotNull String unescapeStringLiteral(@NotNull String raw) {
        String val = raw;
        if (val.startsWith("\"\"\"") && val.endsWith("\"\"\"")) val = val.substring(3, val.length() - 3);
        else if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
        StringBuilder sb = new StringBuilder(val.length());
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            if (c == '\\' && i + 1 < val.length()) {
                char n = val.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '0' -> sb.append('\0');
                    case 's' -> sb.append(' ');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
