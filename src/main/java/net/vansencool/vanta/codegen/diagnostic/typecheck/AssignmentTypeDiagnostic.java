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
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic emitted when a value cannot be assigned to a target slot of the declared type.
 */
public final class AssignmentTypeDiagnostic {

    private AssignmentTypeDiagnostic() {
    }

    /**
     * @param ctx        method context being compiled
     * @param exprGen    expression generator used for super chain walks
     * @param spanOwner  AST node whose span anchors the main highlight
     * @param value      source expression on the right hand side
     * @param targetName what the target slot is (e.g. {@code "variable 'x'"})
     * @param target     declared destination type
     * @param actual     inferred source expression type, null when inference failed
     * @return diagnostic describing the mismatch
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull ExpressionGenerator exprGen, @NotNull AstNode spanOwner, @NotNull Expression value, @NotNull String targetName, @NotNull ResolvedType target, @Nullable ResolvedType actual) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building assignment type diagnostic");
        SpanTable spans = cg.spanTable();
        Span anchor = spans.span(spanOwner);
        if (anchor == null) anchor = spans.span(value);
        if (anchor == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for assignment value at line " + value.line());
        String sourceText = SourceLines.lineAt(cg.source(), anchor.startLine());
        SourceRange range = SourceRange.ofSingleLine(anchor, sourceText.length());
        Span valueSpan = spans.span(value);
        MismatchAnalysis analysis = MismatchAnalysis.classify(ctx, exprGen, targetName, target, actual, value);

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(anchor.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title(analysis.mainTitle())
                .label(analysis.mainLabel())
                .note(analysis.note());
        if (valueSpan != null && valueSpan.startLine() == valueSpan.endLine() && valueSpan != anchor) {
            String actualDisplay = actual == null ? "<unknown>" : TypeCompatibility.display(actual);
            builder.subHighlight(valueSpan.startColumn() - 1, valueSpan.endColumn(), "this expression has type " + actualDisplay);
        }
        for (String help : analysis.helpLines()) builder.help(help);
        return builder.build();
    }
}
