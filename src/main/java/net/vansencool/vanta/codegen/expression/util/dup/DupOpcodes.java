package net.vansencool.vanta.codegen.expression.util.dup;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;

/**
 * Width-aware {@code DUP}/{@code DUP_X1}/{@code DUP_X2} variant selection
 * so category-2 types ({@code long}, {@code double}) use the {@code DUP2*}
 * forms automatically.
 */
public final class DupOpcodes {

    private DupOpcodes() {
    }

    /**
     * @param d JVM type descriptor
     * @return {@code DUP} or {@code DUP2} depending on category
     */
    public static int dup(@NotNull String d) {
        return (d.equals("J") || d.equals("D")) ? Opcodes.DUP2 : Opcodes.DUP;
    }

    /**
     * @param d JVM type descriptor
     * @return {@code DUP_X1} or {@code DUP2_X1} depending on category
     */
    public static int dupX1(@NotNull String d) {
        return (d.equals("J") || d.equals("D")) ? Opcodes.DUP2_X1 : Opcodes.DUP_X1;
    }

    /**
     * @param d JVM type descriptor
     * @return {@code DUP_X2} or {@code DUP2_X2} depending on category
     */
    public static int dupX2(@NotNull String d) {
        return (d.equals("J") || d.equals("D")) ? Opcodes.DUP2_X2 : Opcodes.DUP_X2;
    }
}
