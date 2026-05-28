package net.vansencool.vanta.codegen.diagnostic.util;

import org.jetbrains.annotations.NotNull;

/**
 * Levenshtein edit distance.
 */
public final class StringDistance {

    private StringDistance() {
    }

    /**
     * @return number of single character insertions, deletions, or substitutions
     * needed to turn {@code a} into {@code b}
     */
    public static int levenshtein(@NotNull String a, @NotNull String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }
}
