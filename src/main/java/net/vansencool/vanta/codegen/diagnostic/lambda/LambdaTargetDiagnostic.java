package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic for a lambda whose target functional interface cannot be inferred from context.
 */
public final class LambdaTargetDiagnostic {

    private LambdaTargetDiagnostic() {
    }

    /**
     * @param ctx    method context being compiled
     * @param lambda lambda whose target interface could not be inferred
     * @return diagnostic describing the failure
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull LambdaExpression lambda) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building lambda target diagnostic at line " + lambda.line());
        SpanTable spans = cg.spanTable();
        Span lambdaSpan = spans.span(lambda);
        if (lambdaSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for lambda at line " + lambda.line());
        String sourceText = SourceLines.lineAt(cg.source(), lambdaSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(lambdaSpan, sourceText.length());
        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(lambdaSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot infer the target functional interface for this lambda")
                .label("no target type provided to bind this lambda to")
                .note("a lambda needs an enclosing functional interface, the surrounding expression has no such expected type")
                .help("assign or pass this lambda to a variable whose type is a single abstract method interface (e.g. Runnable, java.util.function.Function)")
                .help("if the lambda is a method argument and an overload is ambiguous, add an explicit cast: ((Runnable) () -> ...)");
        return builder.build();
    }
}
