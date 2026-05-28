package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic for a method reference whose target functional interface cannot be inferred from context.
 */
public final class MethodReferenceTargetDiagnostic {

    private MethodReferenceTargetDiagnostic() {
    }

    /**
     * @param ctx method context being compiled
     * @param ref method reference whose target interface could not be inferred
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building method reference target diagnostic at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for method reference at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());

        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot infer the target functional interface for this method reference")
                .label("no target type provided to bind this method reference to")
                .note("a method reference needs a functional interface as its target, the surrounding expression has no such expected type")
                .help("assign or pass this method reference to a variable whose type is a functional interface (e.g. java.util.function.Function)")
                .help("if it is a method argument and an overload is ambiguous, add an explicit cast: ((Function<X, Y>) Type::name)")
                .build();
    }
}
