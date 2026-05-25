package net.vansencool.vanta.codegen.diagnostic.method;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Diagnostic for {@code Type.method(...)} where {@code Type} resolves but no static method named {@code method} exists.
 */
public final class KnownTypeNoStaticMethodDiagnostic {

    private KnownTypeNoStaticMethodDiagnostic() {
    }

    /**
     * @param ctx          method context being compiled
     * @param call         offending call
     * @param ownerSym     resolved owner type symbol
     * @return diagnostic describing the missing static method
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodCallExpression call, @NotNull TypeSymbol ownerSym) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building known type no static method diagnostic for '" + call.methodName() + "'");
        SpanTable spans = cg.spanTable();
        Span callSpan = spans.span(call);
        if (callSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for call '" + call.methodName() + "' at line " + call.line());
        String sourceText = SourceLines.lineAt(cg.source(), callSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(callSpan, sourceText.length());
        String ownerName = ownerSym.internalName().replace('/', '.');
        int argCount = call.arguments().size();
        String plural = argCount == 1 ? "" : "s";

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(callSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("no static method '" + call.methodName() + "' on type " + ownerName)
                .label(ownerName + " has no callable static method named '" + call.methodName() + "'");
        Span nameSpan = spans.sub(call, "methodName");
        if (nameSpan != null && nameSpan.startLine() == nameSpan.endLine()) {
            builder.subHighlight(nameSpan.startColumn() - 1, nameSpan.endColumn(), "no such static method '" + call.methodName() + "' on " + ownerName);
        }
        builder.note("looked up '" + call.methodName() + "' as a static member of " + ownerName + " with " + argCount + " argument" + plural + ", found none");

        List<MethodSymbol> sameName = MethodSignatures.withName(ownerSym, call.methodName());
        boolean anyMatch = false;
        for (MethodSymbol m : sameName) {
            if (m.isStatic()) {
                anyMatch = true;
                builder.help("did you mean " + ownerName + "#" + m.name() + MethodSignatures.descriptorList(m) + "? (static, takes " + m.parameterTypes().size() + " argument" + (m.parameterTypes().size() == 1 ? "" : "s") + ")");
            } else {
                builder.help("'" + m.name() + "' exists on " + ownerName + " but is instance, call it on an instance: <" + ownerName + " value>." + m.name() + "(...)");
            }
        }
        if (!anyMatch && sameName.isEmpty()) {
            for (String alt : MethodSignatures.closeNames(ownerSym, call.methodName(), 5)) {
                builder.help("did you mean " + ownerName + "#" + alt + "?");
            }
        }
        return builder.build();
    }
}
