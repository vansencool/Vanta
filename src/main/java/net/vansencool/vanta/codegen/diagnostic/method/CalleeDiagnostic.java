package net.vansencool.vanta.codegen.diagnostic.method;

import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Populates a diagnostic for the branch where the receiver type is known but no callable method matches.
 */
public final class CalleeDiagnostic {

    private CalleeDiagnostic() {
    }

    /**
     * Attaches notes and help text describing the missing method on a known receiver type.
     */
    public static void attach(@NotNull DiagnosticBuilder builder, @NotNull MethodContext ctx, @NotNull MethodCallExpression call, @NotNull ResolvedType targetType) {
        String typeName = targetType.internalName() != null ? targetType.internalName().replace('/', '.') : targetType.descriptor();
        int argCount = call.arguments().size();
        String plural = argCount == 1 ? "" : "s";
        builder.title("cannot resolve method '" + call.methodName() + "' on type " + typeName);
        builder.label("no matching method on receiver type");
        builder.note("receiver inferred as " + typeName + ", which has no method named '" + call.methodName() + "' callable with " + argCount + " argument" + plural);
        if (targetType.internalName() == null) return;
        TypeRegistry registry = ctx.methodResolver().classpathManager().typeRegistry();
        TypeSymbol owner = registry.lookup(targetType.internalName());
        if (owner == null) return;
        List<MethodSymbol> sameName = MethodSignatures.withName(owner, call.methodName());
        if (!sameName.isEmpty()) {
            for (MethodSymbol m : sameName) {
                builder.help("did you mean " + typeName + '#' + m.name() + MethodSignatures.descriptorList(m) + "?");
            }
            return;
        }
        for (String alt : MethodSignatures.closeNames(owner, call.methodName(), 5)) {
            builder.help("did you mean " + typeName + '#' + alt + "?");
        }
    }
}
