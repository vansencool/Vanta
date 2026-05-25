package net.vansencool.vanta.exception;

import net.vansencool.vanta.diagnostic.DiagnosticReport;
import net.vansencool.vanta.diagnostic.DiagnosticReportPrinter;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown at the end of a batch compile when one or more files produced errors.
 * Carries the full {@link DiagnosticReport} so callers can render or filter it.
 */
public class MultiCompilationException extends RuntimeException {

    private final @NotNull DiagnosticReport report;

    public MultiCompilationException(@NotNull DiagnosticReport report) {
        super(DiagnosticReportPrinter.format(report), null, false, false);
        this.report = report;
    }

    public @NotNull DiagnosticReport report() {
        return report;
    }
}
