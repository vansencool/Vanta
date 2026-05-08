package net.vansencool.vanta.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Rust styled compiler diagnostic.
 *
 * Construct via {@link DiagnosticBuilder}.
 */
public final class Diagnostic {

    private static final int LONG_LINE_THRESHOLD = 80;

    private final @NotNull Severity severity;
    private final @NotNull String title;
    private final @Nullable String sourceFile;
    private final int line;
    private final @NotNull String sourceText;
    private final int columnStart;
    private final int columnEnd;
    private final @Nullable String underlineLabel;
    private final @NotNull List<SubHighlight> subHighlights;
    private final @NotNull List<ContextLine> contextLines;
    private final @NotNull List<String> notes;
    private final @NotNull List<String> helpLines;

    Diagnostic(@NotNull Severity severity, @NotNull String title, @Nullable String sourceFile, int line, @NotNull String sourceText, int columnStart, int columnEnd, @Nullable String underlineLabel, @NotNull List<SubHighlight> subHighlights, @NotNull List<ContextLine> contextLines, @NotNull List<String> notes, @NotNull List<String> helpLines) {
        this.severity = severity;
        this.title = title;
        this.sourceFile = sourceFile;
        this.line = line;
        this.sourceText = sourceText;
        this.underlineLabel = underlineLabel;
        this.subHighlights = subHighlights;
        this.contextLines = contextLines;
        this.notes = notes;
        this.helpLines = helpLines;
        if (columnStart == -1 && columnEnd == -1) {
            String stripped = sourceText.stripTrailing();
            this.columnStart = stripped.length() - stripped.stripLeading().length();
            this.columnEnd = stripped.length();
        } else {
            this.columnStart = columnStart;
            this.columnEnd = columnEnd;
        }
    }

    public static @NotNull DiagnosticBuilder builder() {
        return new DiagnosticBuilder();
    }

    public @NotNull Severity severity() {
        return severity;
    }

    public @NotNull String title() {
        return title;
    }

    public @Nullable String sourceFile() {
        return sourceFile;
    }

    public int line() {
        return line;
    }

    public @NotNull String sourceText() {
        return sourceText;
    }

    public int columnStart() {
        return columnStart;
    }

    public int columnEnd() {
        return columnEnd;
    }

    public @Nullable String underlineLabel() {
        return underlineLabel;
    }

    public @NotNull List<SubHighlight> subHighlights() {
        return subHighlights;
    }

    public @NotNull List<ContextLine> contextLines() {
        return contextLines;
    }

    public @NotNull List<String> notes() {
        return notes;
    }

    public @NotNull List<String> helpLines() {
        return helpLines;
    }

    public @NotNull String format() {
        StringBuilder sb = new StringBuilder();
        String prefix = severity == Severity.ERROR ? "error" : "warning";
        sb.append(prefix).append(": ").append(title).append('\n');

        String trimmed = sourceText.stripTrailing();
        int maxLine = line;
        for (ContextLine cl : contextLines) if (cl.line() > maxLine) maxLine = cl.line();
        String maxLineNum = String.valueOf(maxLine);
        String gutter = " ".repeat(maxLineNum.length()) + " |";

        String location = sourceFile != null ? sourceFile + ":" + line : "line " + line;
        sb.append("  -> ").append(location).append(": ").append(trimmed.stripLeading()).append('\n');
        sb.append(gutter).append('\n');

        for (ContextLine cl : contextLines) {
            String ctxTrimmed = cl.source().stripTrailing();
            String ctxLineNum = String.valueOf(cl.line());
            String padded = " ".repeat(maxLineNum.length() - ctxLineNum.length()) + ctxLineNum;
            sb.append(padded).append(" | ").append(ctxTrimmed).append('\n');
            int ctxStart = Math.max(0, Math.min(cl.columnStart(), ctxTrimmed.length()));
            int ctxEnd = Math.max(ctxStart + 1, Math.min(cl.columnEnd(), ctxTrimmed.length()));
            sb.append(gutter).append(" ".repeat(ctxStart + 1)).append("~".repeat(Math.max(1, ctxEnd - ctxStart)));
            if (cl.label() != null) sb.append(' ').append(cl.label());
            sb.append('\n');
            sb.append(gutter).append('\n');
            if (cl.line() + 1 < line) {
                sb.append(" ".repeat(maxLineNum.length())).append(" ...").append('\n');
                sb.append(gutter).append('\n');
            }
        }

        String lineNum = String.valueOf(line);
        String paddedLineNum = " ".repeat(maxLineNum.length() - lineNum.length()) + lineNum;
        sb.append(paddedLineNum).append(" | ").append(trimmed).append('\n');

        boolean longLine = !subHighlights.isEmpty() && trimmed.length() > LONG_LINE_THRESHOLD;

        int start = Math.max(0, Math.min(columnStart, trimmed.length()));
        int end = Math.max(start + 1, Math.min(columnEnd, trimmed.length()));
        sb.append(gutter).append(" ".repeat(start + 1)).append("~".repeat(Math.max(1, end - start)));
        if (underlineLabel != null) sb.append(' ').append(underlineLabel);
        sb.append('\n');

        if (!longLine) {
            for (SubHighlight sh : subHighlights) {
                int shStart = Math.max(0, Math.min(sh.columnStart(), trimmed.length()));
                int shEnd = Math.max(shStart + 1, Math.min(sh.columnEnd(), trimmed.length()));
                sb.append(gutter).append(" ".repeat(shStart + 1)).append("~".repeat(Math.max(1, shEnd - shStart)));
                if (sh.label() != null) sb.append(' ').append(sh.label());
                sb.append('\n');
            }
        }
        sb.append(gutter).append('\n');

        if (longLine) {
            for (SubHighlight sh : subHighlights) {
                if (sh.label() == null) continue;
                sb.append("  = note: ").append(sh.label()).append('\n');
            }
        }
        for (String note : notes) sb.append("  = note: ").append(note).append('\n');
        for (String h : helpLines) sb.append("  = help: ").append(h).append('\n');
        return sb.toString();
    }
}
