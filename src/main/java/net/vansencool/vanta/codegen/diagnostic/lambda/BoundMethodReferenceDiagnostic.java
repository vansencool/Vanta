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
 * Diagnostic for a bound instance method reference {@code expr::name} whose receiver type lacks a matching method.
 */
public final class BoundMethodReferenceDiagnostic {

    private BoundMethodReferenceDiagnostic() {
    }

    /**
     * @param ctx         method context being compiled
     * @param ref         offending method reference
     * @param receiverSym resolved receiver type symbol
     * @param samArity    arity of the target functional interface SAM
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref, @NotNull TypeSymbol receiverSym, int samArity) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building bound method reference diagnostic at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for bound method reference at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());
        String receiverName = receiverSym.internalName().replace('/', '.');
        String plural = samArity == 1 ? "" : "s";

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("method reference 'expr::" + ref.methodName() + "' has no matching method on " + receiverName)
                .label("no method '" + ref.methodName() + "' on receiver accepts " + samArity + " argument" + plural)
                .note("looked on '" + receiverName + "' and its super chain for an instance method named '" + ref.methodName() + "' with " + samArity + " parameter" + plural + ", found none");

        List<MethodSymbol> sameName = MethodSignatures.withName(receiverSym, ref.methodName());
        if (!sameName.isEmpty()) {
            for (MethodSymbol m : sameName) {
                builder.help("candidate '" + receiverName + '#' + m.name() + MethodSignatures.descriptorList(m) + "' takes " + m.parameterTypes().size() + " parameter" + (m.parameterTypes().size() == 1 ? "" : "s"));
            }
        } else {
            for (String alt : MethodSignatures.closeNames(receiverSym, ref.methodName(), 5)) {
                builder.help("did you mean expr::" + alt + "?");
            }
        }
        return builder.build();
    }
}
