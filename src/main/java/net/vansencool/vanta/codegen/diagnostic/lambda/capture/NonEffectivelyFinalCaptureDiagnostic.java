package net.vansencool.vanta.codegen.diagnostic.lambda.capture;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.TypeCompatibility;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.fix.Applicability;
import net.vansencool.vanta.diagnostic.fix.Edit;
import net.vansencool.vanta.diagnostic.fix.Fix;
import net.vansencool.vanta.diagnostic.util.SourceLines;

import java.util.List;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Diagnostic for a lambda or anonymous class that captures a local which is
 * reassigned somewhere in the enclosing method.
 */
public final class NonEffectivelyFinalCaptureDiagnostic {

    private NonEffectivelyFinalCaptureDiagnostic() {
    }

    /**
     * @param ctx        method context being compiled
     * @param spanOwner  capturing construct (lambda or anonymous class new expression)
     * @param captured   the local that is captured but mutated
     * @param construct  short label (e.g. {@code "lambda"} or {@code "anonymous class"})
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull AstNode spanOwner, @NotNull LocalVariable captured, @NotNull String construct) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building capture diagnostic for '" + captured.name() + "'");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(spanOwner);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for " + construct + " capturing '" + captured.name() + "'");
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .fullSource(cg.source())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title(construct + " captures '" + captured.name() + "', which is not effectively final")
                .label("captured here")
                .note(article(construct) + ' ' + construct + " can only capture a local that is declared final, or that is assigned exactly once at its declaration and never reassigned");
        Set<Integer> emittedLines = new HashSet<>();
        if (captured.declaration() != null) {
            Span declSpan = spans.span(captured.declaration());
            if (declSpan != null) {
                String declText = SourceLines.lineAt(cg.source(), declSpan.startLine());
                SourceRange declRange = SourceRange.ofSingleLine(declSpan, declText.length());
                builder.context(declSpan.startLine(), declText, declRange.startCol(), declRange.endCol(), "declared here");
                emittedLines.add(declSpan.startLine());
            }
        }
        for (AstNode reassign : ctx.reassignmentSites(captured.name())) {
            Span reSpan = spans.span(reassign);
            if (reSpan == null || reSpan.startLine() == span.startLine()) continue;
            int lineNo = reSpan.startLine();
            String reText = SourceLines.lineAt(cg.source(), lineNo);
            SourceRange reRange = SourceRange.ofSingleLine(reSpan, reText.length());
            String label = lineNo > span.startLine()
                    ? "reassigned later here (disqualifies the capture)"
                    : "reassigned earlier here (disqualifies the capture)";
            int prevLine = lineNo - 1;
            if (prevLine > 0 && prevLine != span.startLine() && emittedLines.add(prevLine)) {
                String prev = SourceLines.lineAt(cg.source(), prevLine);
                if (!prev.isEmpty()) builder.context(prevLine, prev, 0, 0, null);
            }
            if (emittedLines.add(lineNo)) builder.context(lineNo, reText, reRange.startCol(), reRange.endCol(), label);
            int nextLine = lineNo + 1;
            if (nextLine != span.startLine() && emittedLines.add(nextLine)) {
                String next = SourceLines.lineAt(cg.source(), nextLine);
                if (!next.isEmpty()) builder.context(nextLine, next, 0, 0, null);
            }
        }
        if (captured.declaration() != null) {
            Span declSpan = spans.span(captured.declaration());
            if (declSpan != null) {
                String declLine = SourceLines.lineAt(cg.source(), declSpan.startLine());
                int firstNonSpace = 0;
                while (firstNonSpace < declLine.length() && Character.isWhitespace(declLine.charAt(firstNonSpace))) firstNonSpace++;
                int insertAt = positionForFinalKeyword(declLine, firstNonSpace);
                if (insertAt >= 0) {
                    String snippet = declLine.substring(insertAt).stripTrailing();
                    String typeHint = snippet.split("\\s+", 2).length > 0 ? snippet.split("\\s+", 2)[0] : "type";
                    builder.fix(new Fix(
                            "declare '" + captured.name() + "' final",
                            "marks the local final so the capture is permitted (insert before the type '" + typeHint + "')",
                            List.of(Edit.insert(declSpan.startLine(), insertAt, "final ", "insert 'final' before '" + typeHint + "'")),
                            Applicability.MACHINE_APPLICABLE));
                }
                String indent = declLine.substring(0, firstNonSpace);
                String snapshotName = captured.name() + "Snapshot";
                String snapshotLine = indent + "final " + TypeCompatibility.display(captured.type()) + " " + snapshotName + " = " + captured.name() + ";";
                builder.fix(new Fix(
                        "snapshot '" + captured.name() + "' before the " + construct,
                        "captures a frozen copy that survives later reassignments",
                        List.of(
                                Edit.insertLineBefore(span.startLine(), snapshotLine, "insert snapshot line"),
                                Edit.replace(span.startLine(), range.startCol(), range.endCol(), substituteName(sourceText, range, captured.name(), snapshotName), "use snapshot inside")
                        ),
                        Applicability.MAYBE_INCORRECT));
            }
        }
        builder.help("if mutation is needed, store the value in an array slot or AtomicReference and read it inside the " + construct);
        return builder.build();
    }

    /**
     * Finds the column where {@code final} should be inserted on a local
     * variable declaration line. Skips any leading access modifiers and
     * {@code static} so the resulting token order stays {@code <access> <static> final <type>}.
     * Returns {@code -1} when the line already contains {@code final}.
     */
    private static int positionForFinalKeyword(@NotNull String declLine, int firstNonSpace) {
        int cursor = firstNonSpace;
        while (true) {
            int wordEnd = cursor;
            while (wordEnd < declLine.length() && !Character.isWhitespace(declLine.charAt(wordEnd))) wordEnd++;
            String word = declLine.substring(cursor, wordEnd);
            if ("final".equals(word)) return -1;
            if ("public".equals(word) || "private".equals(word) || "protected".equals(word) || "static".equals(word)) {
                cursor = wordEnd;
                while (cursor < declLine.length() && Character.isWhitespace(declLine.charAt(cursor))) cursor++;
                continue;
            }
            return cursor;
        }
    }

    private static @NotNull String substituteName(@NotNull String sourceText, @NotNull SourceRange range, @NotNull String oldName, @NotNull String newName) {
        String slice = sourceText.substring(Math.max(0, range.startCol()), Math.min(sourceText.length(), range.endCol()));
        return slice.replaceAll("\\b" + Pattern.quote(oldName) + "\\b", newName);
    }

    /**
     * @return {@code "a"} or {@code "an"} matching the first vowel sound of {@code word}
     */
    private static @NotNull String article(@NotNull String word) {
        if (word.isEmpty()) return "a";
        char c = Character.toLowerCase(word.charAt(0));
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' ? "an" : "a";
    }
}
