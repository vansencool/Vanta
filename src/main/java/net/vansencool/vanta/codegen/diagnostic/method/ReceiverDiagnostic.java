package net.vansencool.vanta.codegen.diagnostic.method;

import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.CandidateSearch;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Populates a diagnostic for the branch where the call's receiver type could not be inferred.
 */
public final class ReceiverDiagnostic {

    private ReceiverDiagnostic() {
    }

    /**
     * Attaches sub highlights, notes, and help to {@code builder} describing the unresolved receiver.
     */
    public static void attach(@NotNull DiagnosticBuilder builder, @NotNull MethodContext ctx, @NotNull MethodCallExpression call, @NotNull SpanTable spans) {
        String receiverName = receiverHeadName(call);
        builder.title("cannot resolve method '" + call.methodName() + "' on unresolved receiver");
        if (receiverName == null) {
            builder.label("receiver type could not be inferred");
            builder.note("the call's receiver expression has no known type, no method lookup is possible");
            return;
        }
        if (call.target() == null) throw new IllegalStateException("internal compiler error: receiver head name '" + receiverName + "' but call.target() is null at line " + call.line());
        Span receiverSpan = spans.span(call.target());
        if (receiverSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for receiver '" + receiverName + "' at line " + call.line());
        if (receiverSpan.startLine() == receiverSpan.endLine()) {
            builder.subHighlight(receiverSpan.startColumn() - 1, receiverSpan.endColumn(), "receiver '" + receiverName + "' is not in scope");
        }
        builder.label("call site cannot bind to any method");
        builder.note("identifier '" + receiverName + "' is not a local, parameter, field, static import, or imported type");
        List<String> candidates = CandidateSearch.sameSimpleName(ctx.methodResolver().classpathManager().typeRegistry(), receiverName);
        for (String candidate : candidates) {
            builder.help("add: import " + candidate.replace('/', '.') + ";");
        }
        if (candidates.isEmpty()) {
            builder.help("declare '" + receiverName + "' as a local, field, or imported type before calling '" + call.methodName() + "' on it");
        }
    }

    private static @Nullable String receiverHeadName(@NotNull MethodCallExpression call) {
        if (call.target() instanceof NameExpression n) return n.name();
        if (call.target() instanceof FieldAccessExpression fa) {
            if (fa.target() instanceof NameExpression head) return head.name();
        }
        return null;
    }
}
