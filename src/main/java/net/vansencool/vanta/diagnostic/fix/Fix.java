package net.vansencool.vanta.diagnostic.fix;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A user facing suggested fix. Carries a short title, one or more
 * {@link Edit}s, and an {@link Applicability} tag that tells tooling whether
 * the edit can be applied automatically.
 *
 * @param title         one line description of the fix
 * @param prelude       optional explanation rendered before the diff block
 * @param edits         source edits that make up this fix, in source order
 * @param applicability confidence tag for tooling auto apply
 */
public record Fix(@NotNull String title, @Nullable String prelude, @NotNull List<Edit> edits,
                  @NotNull Applicability applicability) {

    public Fix(@NotNull String title, @NotNull List<Edit> edits, @NotNull Applicability applicability) {
        this(title, null, edits, applicability);
    }
}
