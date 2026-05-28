package net.vansencool.vanta.diagnostic.fix;

import net.vansencool.vanta.diagnostic.util.SourceLines;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link Fix} as a patch block: one line per affected source line
 * with a leading {@code -}/{@code +}/space, column underlines describing
 * each edit, optional gap collapsing for distant lines, and an
 * applicability tag.
 */
public final class FixRenderer {

    private static final int CONTEXT_RADIUS = 1;
    private static final int GAP_BEFORE_ELLIPSIS = 1;

    private FixRenderer() {
    }

    /**
     * @param fix           the fix to render
     * @param fullSource    the source text the fix applies to
     * @param sourceFile    file name shown in the location header, may be null
     * @return rendered patch block, never null and always ending with a newline
     */
    public static @NotNull String render(@NotNull Fix fix, @NotNull String fullSource, String sourceFile) {
        StringBuilder sb = new StringBuilder();
        Map<Integer, List<Edit>> byLine = groupByLine(fix.edits());
        int anchorLine = byLine.keySet().iterator().next();
        String location = sourceFile != null ? sourceFile + ":" + anchorLine : "line " + anchorLine;
        int maxLineNum = byLine.keySet().stream().mapToInt(Integer::intValue).max().orElse(anchorLine);
        int width = String.valueOf(maxLineNum + 1).length();
        String pad = " ".repeat(width);

        sb.append("  = fix: ").append(fix.title());
        if (fix.applicability() != Applicability.UNSPECIFIED) {
            sb.append("  [").append(tag(fix.applicability())).append(']');
        }
        sb.append('\n');
        if (fix.prelude() != null) sb.append("        ").append(fix.prelude()).append('\n');
        sb.append("    ").append(pad).append(" ┌─ ").append(location).append('\n');

        String prefix = "    ";
        Integer prevLine = null;
        for (Map.Entry<Integer, List<Edit>> entry : byLine.entrySet()) {
            int line = entry.getKey();
            List<Edit> lineEdits = entry.getValue();
            if (prevLine != null && line - prevLine > 2 * CONTEXT_RADIUS + GAP_BEFORE_ELLIPSIS) {
                sb.append(prefix).append(pad).append(" ⋮\n");
            } else if (prevLine != null && line > prevLine + 1) {
                for (int between = prevLine + 1; between < line; between++) {
                    String btxt = SourceLines.lineAt(fullSource, between).stripTrailing();
                    sb.append(prefix).append(numbered(between, width)).append("   ").append(btxt).append('\n');
                }
            }
            renderLineGroup(sb, line, lineEdits, fullSource, width, prefix);
            prevLine = line;
        }
        return sb.toString();
    }

    /**
     * Groups edits by their anchor line, preserving stable insertion order so
     * the rendered output follows the order in which they were declared.
     */
    private static @NotNull Map<Integer, List<Edit>> groupByLine(@NotNull List<Edit> edits) {
        Map<Integer, List<Edit>> out = new LinkedHashMap<>();
        for (Edit e : edits) out.computeIfAbsent(e.line(), k -> new ArrayList<>()).add(e);
        for (List<Edit> list : out.values()) list.sort(Comparator.comparingInt(Edit::colStart));
        return out;
    }

    /**
     * Emits the original line marked with {@code -}, the patched line marked
     * with {@code +}, and a column underline showing where each edit applies.
     * For pure insertion edits (anchored line untouched textually) the
     * {@code -} row is suppressed so the diff stays minimal.
     */
    private static void renderLineGroup(@NotNull StringBuilder sb, int line, @NotNull List<Edit> edits, @NotNull String fullSource, int width, @NotNull String prefix) {
        if (containsLineInsertOnly(edits)) {
            renderLineInsertOnly(sb, line, edits, fullSource, width, prefix);
            return;
        }
        List<Edit> lineInsertsBefore = new ArrayList<>();
        List<Edit> lineInsertsAfter = new ArrayList<>();
        for (Edit e : edits) {
            if (e.kind() == EditKind.INSERT_LINE_BEFORE) lineInsertsBefore.add(e);
            else if (e.kind() == EditKind.INSERT_LINE_AFTER) lineInsertsAfter.add(e);
        }
        for (Edit e : lineInsertsBefore) renderLineInsert(sb, e, fullSource, width, prefix);
        String original = SourceLines.lineAt(fullSource, line);
        String originalTrimmed = original.stripTrailing();
        String patched = applyInlineEdits(original, edits);
        boolean originalChanged = !patched.equals(original.stripTrailing());
        if (originalChanged) {
            sb.append(prefix).append(numbered(line, width)).append(" - ").append(originalTrimmed).append('\n');
        } else {
            sb.append(prefix).append(numbered(line, width)).append("   ").append(originalTrimmed).append('\n');
        }
        for (Edit e : edits) renderUnderline(sb, e, originalTrimmed, width, prefix);
        if (originalChanged && !patched.isBlank()) {
            sb.append(prefix).append(numbered(line, width)).append(" + ").append(patched.stripTrailing()).append('\n');
        }
        for (Edit e : lineInsertsAfter) renderLineInsert(sb, e, fullSource, width, prefix);
    }

    /**
     * Picks the right underline glyph for an edit kind: {@code +++++} for
     * insertions, {@code -----} for deletions, {@code ~~~~~} for replacements.
     */
    private static void renderUnderline(@NotNull StringBuilder sb, @NotNull Edit e, @NotNull String originalLine, int width, @NotNull String prefix) {
        if (e.kind() == EditKind.INSERT_LINE_AFTER || e.kind() == EditKind.INSERT_LINE_BEFORE) return;
        int colStart = Math.max(0, Math.min(e.colStart(), originalLine.length()));
        int colEnd = Math.max(colStart, Math.min(e.colEnd(), originalLine.length()));
        char glyph = switch (e.kind()) {
            case INSERT -> '+';
            case DELETE -> '-';
            case REPLACE -> '~';
            default -> ' ';
        };
        int length = e.kind() == EditKind.INSERT ? Math.max(1, e.replacement().length()) : Math.max(1, colEnd - colStart);
        sb.append(prefix).append(" ".repeat(width)).append(" │ ").append(" ".repeat(colStart)).append(String.valueOf(glyph).repeat(length));
        if (e.label() != null) sb.append(" ").append(e.label());
        sb.append('\n');
    }

    /**
     * Renders a whole new line inserted before or after an anchor line. Shows
     * the source anchor line followed by the new content prefixed with
     * {@code +}.
     */
    private static void renderLineInsert(@NotNull StringBuilder sb, @NotNull Edit e, @NotNull String fullSource, int width, @NotNull String prefix) {
        int targetLine = e.kind() == EditKind.INSERT_LINE_AFTER ? e.line() + 1 : e.line();
        for (String l : e.replacement().split("\n", -1)) {
            sb.append(prefix).append(numbered(targetLine, width)).append(" + ").append(l).append('\n');
            if (e.label() != null) {
                sb.append(prefix).append(" ".repeat(width)).append(" │ ").append(e.label()).append('\n');
                break;
            }
        }
    }

    /**
     * Renders a fix whose only edits are full line inserts (no inline edits).
     * Shows the surrounding source lines so the user sees where the inserted
     * lines land relative to existing code.
     */
    private static void renderLineInsertOnly(@NotNull StringBuilder sb, int line, @NotNull List<Edit> edits, @NotNull String fullSource, int width, @NotNull String prefix) {
        String anchorText = SourceLines.lineAt(fullSource, line).stripTrailing();
        sb.append(prefix).append(numbered(line, width)).append("   ").append(anchorText).append('\n');
        for (Edit e : edits) renderLineInsert(sb, e, fullSource, width, prefix);
    }

    /**
     * Applies every inline edit (INSERT / REPLACE / DELETE) to {@code original}
     * in reverse column order so earlier offsets stay valid.
     */
    private static @NotNull String applyInlineEdits(@NotNull String original, @NotNull List<Edit> edits) {
        List<Edit> inline = new ArrayList<>();
        for (Edit e : edits) {
            if (e.kind() == EditKind.INSERT || e.kind() == EditKind.REPLACE || e.kind() == EditKind.DELETE) inline.add(e);
        }
        inline.sort(Comparator.comparingInt(Edit::colStart).reversed());
        StringBuilder buf = new StringBuilder(original);
        for (Edit e : inline) {
            int start = Math.max(0, Math.min(e.colStart(), buf.length()));
            int end = Math.max(start, Math.min(e.colEnd(), buf.length()));
            buf.replace(start, end, e.replacement());
        }
        return buf.toString();
    }

    /**
     * @return {@code true} when every edit anchored at the current line is a
     * whole line insert (no inline patch needed)
     */
    private static boolean containsLineInsertOnly(@NotNull List<Edit> edits) {
        for (Edit e : edits) if (e.kind() != EditKind.INSERT_LINE_AFTER && e.kind() != EditKind.INSERT_LINE_BEFORE) return false;
        return true;
    }

    private static @NotNull String numbered(int line, int width) {
        String s = String.valueOf(line);
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * @return short tag rendered in brackets after the fix title
     */
    private static @NotNull String tag(@NotNull Applicability a) {
        return switch (a) {
            case MACHINE_APPLICABLE -> "machine applicable";
            case MAYBE_INCORRECT -> "maybe incorrect";
            case HAS_PLACEHOLDER -> "has placeholder";
            case UNSPECIFIED -> "hint";
        };
    }
}
