package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.method.MethodSignatures;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Diagnostic for a method reference {@code Type::name} that has no matching method on the receiver.
 */
public final class MethodReferenceDiagnostic {

    private MethodReferenceDiagnostic() {
    }

    /**
     * @param ctx       method context being compiled
     * @param ref       method reference expression
     * @param ownerSym  resolved owner type symbol where lookup happened
     * @param samArity  arity of the target functional interface SAM
     * @return diagnostic describing the failure
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref, @NotNull TypeSymbol ownerSym, int samArity) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building method reference diagnostic for '" + ref.methodName() + "' at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for method reference '" + ref.methodName() + "' at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());
        String ownerName = ownerSym.internalName().replace('/', '.');

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("method reference '" + ownerName + "::" + ref.methodName() + "' has no matching method")
                .label("no method on " + ownerName + " matches the target SAM arity")
                .note("the target functional interface expects a method taking " + samArity + " argument" + (samArity == 1 ? "" : "s") + " (static) or " + (samArity - 1) + " argument" + (samArity - 1 == 1 ? "" : "s") + " (instance bound by the unbound receiver)");

        List<MethodSymbol> sameName = MethodSignatures.withName(ownerSym, ref.methodName());
        if (!sameName.isEmpty()) {
            for (MethodSymbol m : sameName) {
                String kind = m.isStatic() ? "static" : "instance";
                builder.help("candidate " + kind + ' ' + ownerName + '#' + m.name() + MethodSignatures.descriptorList(m) + " takes " + m.parameterTypes().size() + " argument" + (m.parameterTypes().size() == 1 ? "" : "s"));
            }
        } else {
            for (String alt : MethodSignatures.closeNames(ownerSym, ref.methodName(), 5)) {
                builder.help("did you mean " + ownerName + "::" + alt + "?");
            }
        }
        return builder.build();
    }
}
