package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.CandidateSearch;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Diagnostic for a method reference whose receiver expression has no inferable type.
 */
public final class MethodReferenceReceiverDiagnostic {

    private MethodReferenceReceiverDiagnostic() {
    }

    /**
     * @param ctx method context being compiled
     * @param ref method reference whose receiver could not be resolved
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building method reference receiver diagnostic at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for method reference at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());
        String receiverName = headName(ref);

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol());

        if (receiverName == null) {
            return builder
                    .title("cannot resolve receiver of method reference '::" + ref.methodName() + "'")
                    .label("receiver expression has no inferable type")
                    .note("the expression before '::' must evaluate to a known type or refer to a known class")
                    .build();
        }

        Span receiverSpan = ref.target() == null ? null : spans.span(ref.target());
        if (receiverSpan != null && receiverSpan.startLine() == receiverSpan.endLine()) {
            builder.subHighlight(receiverSpan.startColumn() - 1, receiverSpan.endColumn(), "receiver '" + receiverName + "' is not in scope");
        }

        builder.title("cannot resolve receiver '" + receiverName + "' of method reference '" + receiverName + "::" + ref.methodName() + "'")
                .label("'" + receiverName + "' is neither a known local, field, nor imported type")
                .note("the expression before '::' must be a value of a known type or the name of an imported class");

        List<String> candidates = CandidateSearch.sameSimpleName(ctx.methodResolver().classpathManager().typeRegistry(), receiverName);
        for (String candidate : candidates) {
            builder.help("add: import " + candidate.replace('/', '.') + ";");
        }
        if (candidates.isEmpty()) {
            builder.help("declare '" + receiverName + "' as a local, field, or imported type before referencing '" + ref.methodName() + "' on it");
        }
        return builder.build();
    }

    private static @Nullable String headName(@NotNull MethodReferenceExpression ref) {
        if (ref.target() instanceof NameExpression n) return n.name();
        if (ref.target() instanceof FieldAccessExpression fa && fa.target() instanceof NameExpression head) return head.name();
        return null;
    }
}
