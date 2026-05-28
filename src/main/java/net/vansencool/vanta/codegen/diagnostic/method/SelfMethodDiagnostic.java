package net.vansencool.vanta.codegen.diagnostic.method;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.StringDistance;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Diagnostic for an unqualified call that does not match any method on this class or its super chain.
 */
public final class SelfMethodDiagnostic {

    private SelfMethodDiagnostic() {
    }

    /**
     * @param ctx  method context being compiled
     * @param call unqualified method call that did not resolve to any self method
     * @return diagnostic describing the failure
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodCallExpression call) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building self method diagnostic for call '" + call.methodName() + "' at line " + call.line());
        SpanTable spans = cg.spanTable();
        Span callSpan = spans.span(call);
        if (callSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for self method call '" + call.methodName() + "' at line " + call.line());
        String sourceText = SourceLines.lineAt(cg.source(), callSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(callSpan, sourceText.length());
        String ownerName = ctx.classInternalName().replace('/', '.');
        int argCount = call.arguments().size();
        String plural = argCount == 1 ? "" : "s";

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(callSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot resolve method '" + call.methodName() + "' on " + ownerName)
                .label("no method in this class accepts " + argCount + " argument" + plural)
                .note("looked up '" + call.methodName() + "' on " + ownerName + ", its super chain, and statically imported owners, none matched");

        TreeSet<String> declared = new TreeSet<>();
        for (String key : ctx.selfMethods().keySet()) {
            int colon = key.indexOf(':');
            if (colon > 0) declared.add(key.substring(0, colon));
        }
        int threshold = Math.max(2, call.methodName().length() / 2);
        List<String> close = new ArrayList<>();
        for (String name : declared) {
            if (StringDistance.levenshtein(call.methodName(), name) <= threshold) close.add(name);
        }
        for (int i = 0; i < close.size() && i < 5; i++) {
            builder.help("did you mean " + ownerName + '#' + close.get(i) + "?");
        }
        return builder.build();
    }
}
