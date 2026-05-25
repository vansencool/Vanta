package net.vansencool.vanta.diagnostic;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Renders a {@link DiagnosticReport} as a file tree so all errors in a batch
 * compile show up grouped per file (and per method) in one block.
 */
public final class DiagnosticReportPrinter {

    private DiagnosticReportPrinter() {
    }

    /**
     * @param report accumulated diagnostics
     * @return rendered tree text, empty when the report is empty
     */
    public static @NotNull String format(@NotNull DiagnosticReport report) {
        StringBuilder sb = new StringBuilder();
        List<DiagnosticReport.FileGroup> files = report.fileGroups();
        for (int i = 0; i < files.size(); i++) {
            DiagnosticReport.FileGroup file = files.get(i);
            sb.append(file.file()).append('\n');
            List<DiagnosticReport.MethodGroup> methods = file.methodGroups();
            for (int j = 0; j < methods.size(); j++) {
                boolean lastMethod = j == methods.size() - 1;
                DiagnosticReport.MethodGroup method = methods.get(j);
                String methodLabel = method.method().isEmpty() ? "(top level)" : method.method();
                sb.append(lastMethod ? "└── " : "├── ").append(methodLabel).append('\n');
                List<Diagnostic> diags = method.diagnostics();
                String methodIndent = lastMethod ? "    " : "│   ";
                for (int k = 0; k < diags.size(); k++) {
                    boolean lastDiag = k == diags.size() - 1;
                    appendIndented(sb, methodIndent, lastDiag ? "└── " : "├── ", lastDiag ? "    " : "│   ", diags.get(k).format());
                }
            }
            if (i < files.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Prepends each line of {@code body} with {@code parentIndent} plus the
     * appropriate branch glyph (first line) or continuation glyph (rest).
     */
    private static void appendIndented(@NotNull StringBuilder sb, @NotNull String parentIndent, @NotNull String firstGlyph, @NotNull String contGlyph, @NotNull String body) {
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(parentIndent);
            sb.append(i == 0 ? firstGlyph : contGlyph);
            sb.append(lines[i]).append('\n');
        }
    }
}
