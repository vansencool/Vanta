package net.vansencool.vanta.codegen.statement.sw;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Resolves switch case label expressions to their integer dispatch keys:
 * enum ordinals, literal int or char values, hashed string literals, or
 * static final int constants.
 */
public final class SwitchKeyResolver {

    private final @NotNull MethodContext ctx;

    /**
     * @param ctx owning method context
     */
    public SwitchKeyResolver(@NotNull MethodContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @param internalName candidate internal class name
     * @return true when {@code internalName} resolves to an enum class
     */
    public boolean isEnum(@NotNull String internalName) {
        Class<?> c = ctx.methodResolver().classpathManager().loadClass(internalName);
        return c != null && c.isEnum();
    }

    /**
     * Returns the declaration order index of an enum constant, falling back
     * to {@code AsmClassInfo} when reflection's {@code getEnumConstants()}
     * fails (for example during batch compilation where only in memory
     * skeletons exist and the skeleton's {@code values()} stub throws
     * {@code AbstractMethodError}).
     *
     * @param enumInternal enum class internal name
     * @param constantName declared enum constant name
     * @return ordinal of {@code constantName}, or null when unresolved
     */
    public @Nullable Integer enumOrdinalFor(@NotNull String enumInternal, @NotNull String constantName) {
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
            for (AsmClassInfo.FieldInfo f : info.fields()) {
                if ((f.access() & Opcodes.ACC_ENUM) == 0) continue;
                if (f.name().equals(constantName)) return ord;
                ord++;
            }
        }
        return null;
    }

    /**
     * @param label   case label expression
     * @param enumSel true when the switch selector is an enum
     * @param selType selector resolved type
     * @return integer dispatch key for the label
     */
    public int caseKey(@NotNull Expression label, boolean enumSel, @Nullable ResolvedType selType) {
        if (enumSel && selType != null && selType.internalName() != null && label instanceof NameExpression ne) {
            String selInternal = selType.internalName();
            if (ctx.classGenerator() != null && ctx.classGenerator().hasExternalEnumSwitch(selInternal)) {
                Integer mapped = ctx.classGenerator().externalEnumSwitchKey(selInternal, ne.name());
                if (mapped != null) return mapped;
            }
            Integer ord = enumOrdinalFor(selInternal, ne.name());
            if (ord != null) return ord;
        }
        if (label instanceof LiteralExpression lit) {
            if (lit.literalType() == TokenType.INT_LITERAL) {
                return Integer.parseInt(lit.value().replace("_", ""));
            }
            if (lit.literalType() == TokenType.CHAR_LITERAL) {
                return LiteralParser.parseCharLiteral(lit.value());
            }
            if (lit.literalType() == TokenType.STRING_LITERAL) {
                String v = lit.value();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                return v.hashCode();
            }
        }
        Integer constVal = resolveConstInt(label);
        if (constVal != null) return constVal;
        return 0;
    }

    /**
     * @param expr expression that may resolve to a static final int
     * @return constant value, or null when not a recognised constant
     */
    private @Nullable Integer resolveConstInt(@NotNull Expression expr) {
        if (expr instanceof NameExpression ne) {
            Integer fromSelf = readStaticFinalInt(ctx.classInternalName(), ne.name());
            if (fromSelf != null) return fromSelf;
            String owner = ctx.typeResolver().resolveStaticFieldOwner(ne.name());
            if (owner != null) {
                Integer fromImport = readStaticFinalInt(owner, ne.name());
                if (fromImport != null) return fromImport;
            }
        }
        if (expr instanceof FieldAccessExpression fa && fa.target() instanceof NameExpression nt) {
            String ownerInternal = ctx.typeResolver().resolveInternalName(new TypeNode(nt.name(), null, 0, fa.line()));
            return readStaticFinalInt(ownerInternal, fa.fieldName());
        }
        return null;
    }

    /**
     * Reads a static final {@code int} (or {@code char}, widened to int) from
     * a class. Tries reflection first, then falls back to the asm class info
     * constant value attribute so same compilation skeleton classes (whose
     * stubs do not expose real field values) still yield a usable switch key.
     *
     * @param ownerInternal owner class internal name
     * @param fieldName     field name
     * @return constant value as int, or null when unresolved
     */
    private @Nullable Integer readStaticFinalInt(@NotNull String ownerInternal, @NotNull String fieldName) {
        Class<?> cls = ctx.methodResolver().classpathManager().loadClass(ownerInternal);
        if (cls != null) {
            Field f = ClasspathManager.safeGetField(cls, fieldName);
            if (f != null && Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                try {
                    Object v = f.get(null);
                    if (v instanceof Integer i) return i;
                    if (v instanceof Character c) return (int) c;
                } catch (Throwable ignored) {
                }
            }
        }
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(ownerInternal);
        if (info != null) {
            for (AsmClassInfo.FieldInfo fi : info.fields()) {
                if (!fi.name().equals(fieldName)) continue;
                int acc = fi.access();
                if (!Modifier.isStatic(acc) || !Modifier.isFinal(acc)) continue;
                Object v = fi.constantValue();
                if (v instanceof Integer i) return i;
                if (v instanceof Character c) return (int) c;
            }
        }
        return null;
    }
}
