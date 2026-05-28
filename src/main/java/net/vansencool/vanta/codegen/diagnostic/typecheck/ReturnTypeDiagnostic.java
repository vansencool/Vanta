package net.vansencool.vanta.codegen.diagnostic.typecheck;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.TypeCompatibility;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.parser.ast.statement.ReturnStatement;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic emitted when the value of a return statement does not match the enclosing method's declared return type.
 */
public final class ReturnTypeDiagnostic {

    private ReturnTypeDiagnostic() {
    }

    /**
     * @param ctx        method context being compiled
     * @param exprGen    expression generator used for super chain walks
     * @param ret        return statement
     * @param value      returned expression
     * @param expected   declared return type of the enclosing method
     * @param actual     inferred type of {@code value}, null when inference failed
     * @return diagnostic describing the mismatch
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull ExpressionGenerator exprGen, @NotNull ReturnStatement ret, @NotNull Expression value, @NotNull ResolvedType expected, @Nullable ResolvedType actual) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building return type diagnostic");
        SpanTable spans = cg.spanTable();
        Span anchor = spans.span(ret);
        if (anchor == null) anchor = spans.span(value);
        if (anchor == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for return at line " + ret.line());
        String sourceText = SourceLines.lineAt(cg.source(), anchor.startLine());
        SourceRange range = SourceRange.ofSingleLine(anchor, sourceText.length());
        Span valueSpan = spans.span(value);
        MismatchAnalysis analysis = MismatchAnalysis.classify(ctx, exprGen, "return type", expected, actual, value);

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(anchor.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("return type mismatch: method expects " + TypeCompatibility.display(expected) + ", expression returns " + (actual == null ? "<unknown>" : TypeCompatibility.display(actual)))
                .label(analysis.mainLabel())
                .note(analysis.note());
        if (valueSpan != null && valueSpan.startLine() == valueSpan.endLine() && valueSpan != anchor) {
            builder.subHighlight(valueSpan.startColumn() - 1, valueSpan.endColumn(), "this expression has type " + (actual == null ? "<unknown>" : TypeCompatibility.display(actual)));
        }
        for (String help : analysis.helpLines()) builder.help(help);
        return builder.build();
    }
}
