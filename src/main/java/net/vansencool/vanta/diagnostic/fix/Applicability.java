package net.vansencool.vanta.diagnostic.fix;

/**
 * How confident the compiler is that a suggested fix is correct.
 *
 * <ul>
 *   <li>{@link #MACHINE_APPLICABLE} - safe to apply automatically. The edit is
 *   syntactically and semantically equivalent to a known working pattern.</li>
 *   <li>{@link #MAYBE_INCORRECT} - usually correct but might need user
 *   adjustment (rename, placeholder, etc.).</li>
 *   <li>{@link #HAS_PLACEHOLDER} - contains a placeholder token like
 *   {@code <value>} that the user must replace.</li>
 *   <li>{@link #UNSPECIFIED} - hint only, never apply automatically.</li>
 * </ul>
 */
public enum Applicability {
    MACHINE_APPLICABLE,
    MAYBE_INCORRECT,
    HAS_PLACEHOLDER,
    UNSPECIFIED
}
