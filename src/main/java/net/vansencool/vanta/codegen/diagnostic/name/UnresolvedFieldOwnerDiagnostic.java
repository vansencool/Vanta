package net.vansencool.vanta.codegen.diagnostic.name;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.CandidateSearch;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Diagnostic for {@code Owner.field} where {@code Owner} resolves to no known type.
 */
public final class UnresolvedFieldOwnerDiagnostic {

    private UnresolvedFieldOwnerDiagnostic() {
    }

    /**
     * @param ctx         method context being compiled
     * @param fieldAccess offending field access expression
     * @param ownerName   unqualified owner name written in source
     * @return diagnostic describing the unresolved owner
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull FieldAccessExpression fieldAccess, @NotNull String ownerName) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building unresolved field owner diagnostic for '" + ownerName + "." + fieldAccess.fieldName() + "'");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(fieldAccess);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for field access '" + ownerName + "." + fieldAccess.fieldName() + "' at line " + fieldAccess.line());
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot resolve owner '" + ownerName + "' of field access '" + ownerName + "." + fieldAccess.fieldName() + "'")
                .label("'" + ownerName + "' is not a known type or in scope variable")
                .note("the expression before '.' must be a known type (for a static field) or a variable holding an instance");

        if (selfMethodWithName(ctx, ownerName)) {
            builder.help("'" + ownerName + "' is a method on this class, call it instead: " + ownerName + "()." + fieldAccess.fieldName());
        }
        List<String> candidates = CandidateSearch.sameSimpleName(ctx.methodResolver().classpathManager().typeRegistry(), ownerName);
        for (String candidate : candidates) builder.help("add: import " + candidate.replace('/', '.') + ";");
        if (candidates.isEmpty()) {
            builder.help("declare '" + ownerName + "' as a local, field, or imported type before accessing '." + fieldAccess.fieldName() + "' on it");
        }
        return builder.build();
    }

    private static boolean selfMethodWithName(@NotNull MethodContext ctx, @NotNull String name) {
        for (Map.Entry<String, SelfMethodInfo> e : ctx.selfMethods().entrySet()) {
            String key = e.getKey();
            int colon = key.indexOf(':');
            if (colon > 0 && name.equals(key.substring(0, colon))) return true;
        }
        return false;
    }
}
