package net.vansencool.vanta.codegen.diagnostic.typecheck;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.TypeCompatibility;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic for a binary operator whose operand types the JLS does not allow.
 */
public final class BinaryOperandDiagnostic {

    private BinaryOperandDiagnostic() {
    }

    /**
     * @param ctx     method context being compiled
     * @param binary  offending binary expression
     * @param leftType inferred type of the left operand, may be null
     * @param rightType inferred type of the right operand, may be null
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull BinaryExpression binary, @Nullable ResolvedType leftType, @Nullable ResolvedType rightType) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building binary operand diagnostic for `" + binary.operator() + "`");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(binary);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for binary expression at line " + binary.line());
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());
        String leftLabel = leftType == null ? "?" : TypeCompatibility.display(leftType);
        String rightLabel = rightType == null ? "?" : TypeCompatibility.display(rightType);
        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .fullSource(cg.source())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("operator `" + binary.operator() + "` cannot be applied to `" + leftLabel + "` and `" + rightLabel + "`")
                .label("operands are not numeric (and for `+`, neither is a String)")
                .note("`" + binary.operator() + "` requires both operands to be primitive numeric, except `+` which also permits at least one String")
                .build();
    }
}
