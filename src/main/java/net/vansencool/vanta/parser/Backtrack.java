package net.vansencool.vanta.parser;

/**
 * Internal control flow signal that a speculative parse path failed to match.
 * Never propagates out of the parser; never seen by callers.
 */
public final class Backtrack extends RuntimeException {

    public static final Backtrack INSTANCE = new Backtrack();

    private Backtrack() {
        super(null, null, false, false);
    }
}
