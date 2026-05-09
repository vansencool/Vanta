package net.vansencool.vanta.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class DiagnosticBuilder {

    private final @NotNull List<SubHighlight> subHighlights = new ArrayList<>();
    private final @NotNull List<ContextLine> contextLines = new ArrayList<>();
    private final @NotNull List<String> notes = new ArrayList<>();
    private final @NotNull List<String> helpLines = new ArrayList<>();
    private @NotNull Severity severity = Severity.ERROR;
    private @NotNull String title = "";
    private @Nullable String sourceFile = null;
    private int line;
    private @NotNull String sourceText = "";
    private int columnStart = -1;
    private int columnEnd = -1;
    private @Nullable String underlineLabel = null;

    DiagnosticBuilder() {
    }

    public @NotNull DiagnosticBuilder severity(@NotNull Severity severity) {
        this.severity = severity;
        return this;
    }

    public @NotNull DiagnosticBuilder title(@NotNull String title) {
        this.title = title;
        return this;
    }

    public @NotNull DiagnosticBuilder sourceFile(@Nullable String sourceFile) {
        this.sourceFile = sourceFile;
        return this;
    }

    public @NotNull DiagnosticBuilder at(int line, @NotNull String sourceText) {
        this.line = line;
        this.sourceText = sourceText;
        return this;
    }

    public @NotNull DiagnosticBuilder highlight(int columnStart, int columnEnd) {
        this.columnStart = columnStart;
        this.columnEnd = columnEnd;
        return this;
    }

    public @NotNull DiagnosticBuilder label(@NotNull String label) {
        this.underlineLabel = label;
        return this;
    }

    public @NotNull DiagnosticBuilder subHighlight(int columnStart, int columnEnd, @Nullable String label) {
        this.subHighlights.add(new SubHighlight(columnStart, columnEnd, label));
        return this;
    }

    public @NotNull DiagnosticBuilder context(int line, @NotNull String source, int columnStart, int columnEnd, @Nullable String label) {
        this.contextLines.add(new ContextLine(line, source, columnStart, columnEnd, label));
        return this;
    }

    public @NotNull DiagnosticBuilder note(@NotNull String note) {
        this.notes.add(note);
        return this;
    }

    public @NotNull DiagnosticBuilder help(@NotNull String help) {
        this.helpLines.add(help);
        return this;
    }

    public @NotNull Diagnostic build() {
        return new Diagnostic(severity, title, sourceFile, line, sourceText, columnStart, columnEnd, underlineLabel, List.copyOf(subHighlights), List.copyOf(contextLines), List.copyOf(notes), List.copyOf(helpLines));
    }
}
