package net.vansencool.vanta.codegen.diagnostic.name;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.CandidateSearch;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.StringDistance;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic for a bare identifier that does not resolve to any local, field, or known type.
 */
public final class UnresolvedNameDiagnostic {

    private UnresolvedNameDiagnostic() {
    }

    /**
     * @param ctx  method context being compiled
     * @param name offending name expression
     * @return diagnostic describing the unresolved identifier
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull NameExpression name) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building unresolved name diagnostic for '" + name.name() + "'");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(name);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for name '" + name.name() + "' at line " + name.line());
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot resolve identifier '" + name.name() + "'")
                .label("'" + name.name() + "' is not in scope")
                .note("'" + name.name() + "' is not a local, parameter, captured outer local, field of this class or its super chain, statically imported member, or imported type");

        List<String> typeCandidates = CandidateSearch.sameSimpleName(ctx.methodResolver().classpathManager().typeRegistry(), name.name());
        for (String candidate : typeCandidates) builder.help("add: import " + candidate.replace('/', '.') + ";");

        List<String> localCandidates = closeLocalsAndFields(ctx, name.name());
        for (String alt : localCandidates) builder.help("did you mean '" + alt + "'?");

        if (typeCandidates.isEmpty() && localCandidates.isEmpty()) {
            builder.help("declare '" + name.name() + "' as a local, parameter, or field before reading it");
        }
        return builder.build();
    }

    private static @NotNull List<String> closeLocalsAndFields(@NotNull MethodContext ctx, @NotNull String wanted) {
        List<String> candidates = new ArrayList<>();
        for (String n : ctx.scope().allNames()) candidates.add(n);
        int threshold = Math.max(2, wanted.length() / 2);
        List<String> out = new ArrayList<>();
        for (String n : candidates) {
            if (StringDistance.levenshtein(wanted, n) <= threshold) out.add(n);
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }
}
