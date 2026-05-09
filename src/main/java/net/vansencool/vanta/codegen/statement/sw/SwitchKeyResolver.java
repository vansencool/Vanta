package net.vansencool.vanta.codegen.statement.sw;

import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.type.ResolvedType;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

/**
 * Resolves switch case label expressions to their integer dispatch keys:
 * enum ordinals, literal int or char values, hashed string literals, or
 * static final int constants.
 */
public final class SwitchKeyResolver {

    private final @NotNull MethodContext ctx;

    public SwitchKeyResolver(@NotNull MethodContext ctx) {
        this.ctx = ctx;
    }

    public boolean isEnum(@NotNull String internalName) {
        TypeSymbol sym = registry().lookup(internalName);
        return sym != null && sym.isEnum();
    }

    /**
     * Returns the declaration order index of an enum constant by walking
     * the owner symbol's fields and counting {@code ACC_ENUM} entries.
     */
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
     * Reads a static final {@code int} (or {@code char}, widened to int)
     * from {@code ownerInternal} via the type registry. Walks the owner's
     * fields and supers so inherited constants resolve too.
     */
    private @Nullable Integer readStaticFinalInt(@NotNull String ownerInternal, @NotNull String fieldName) {
        TypeSymbol sym = registry().lookup(ownerInternal);
        if (sym == null) return null;
        FieldSymbol field = findField(sym, fieldName);
        if (field == null) return null;
        if (!field.isStatic() || !field.isFinal()) return null;
        Object v = field.constantValue();
        if (v instanceof Integer i) return i;
        if (v instanceof Character c) return (int) c;
        return null;
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

    private @NotNull TypeRegistry registry() {
        return ctx.methodResolver().classpathManager().typeRegistry();
    }
}
