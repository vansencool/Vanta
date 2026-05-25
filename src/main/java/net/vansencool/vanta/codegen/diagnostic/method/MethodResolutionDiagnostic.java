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
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the diagnostic thrown when a method call cannot be resolved.
 */
public final class MethodResolutionDiagnostic {

    private MethodResolutionDiagnostic() {
    }

    /**
     * @param ctx        method context being compiled
     * @param call       unresolved method call
     * @param targetType inferred receiver type, or null when inference failed
     * @return diagnostic describing the failure
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodCallExpression call, @Nullable ResolvedType targetType) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building diagnostic for call '" + call.methodName() + "' at line " + call.line());
        SpanTable spans = cg.spanTable();
        Span callSpan = spans.span(call);
        if (callSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for method call '" + call.methodName() + "' at line " + call.line());
        String source = cg.source();
        String sourceFile = cg.sourceFile();
        int line = callSpan.startLine();
        String sourceText = SourceLines.lineAt(source, line);
        SourceRange range = SourceRange.ofSingleLine(callSpan, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(sourceFile)
                .at(line, sourceText)
                .highlight(range.startCol(), range.endCol());

        if (targetType == null) {
            ReceiverDiagnostic.attach(builder, ctx, call, spans);
        } else {
            CalleeDiagnostic.attach(builder, ctx, call, targetType);
        }
        return builder.build();
    }
}
