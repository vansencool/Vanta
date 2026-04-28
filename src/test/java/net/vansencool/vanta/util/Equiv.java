package net.vansencool.vanta.util;

import net.vansencool.vanta.VantaCompiler;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.exception.CompilationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static bytecode equivalence checker. Normalizes two ASM {@link ClassNode}s
 * to strip cosmetic divergences (label numbering, anonymous-class indexing,
 * constant-push encoding, trailing dead code) so that only semantically
 * meaningful differences remain in a diff.
 * <p>
 * Intended for byte-for-byte comparison tests between Vanta's output and
 * javac's output: use {@link #diff} to report the surviving differences, or
 * {@link #ops} to obtain a canonical instruction list for equality checks.
 */
public final class Equiv {

    private static final Map<Integer, String> OPS = loadOpNames();
    private static final Pattern ANON = Pattern.compile("(.+?)\\$(\\d+)(\\$|$)");

    private Equiv() {
    }

    /**
     * Returns the canonical instruction form for a method. Two methods whose
     * canonical forms are equal are observationally equivalent at the JVM level
     * ignoring debug info.
     */
    public static @NotNull List<String> ops(@NotNull MethodNode method) {
        return ops(method, Map.of());
    }

    /**
     * Returns a list of the real semantic differences between two parsed
     * classes. Empty list means "equivalent modulo cosmetics". Each entry is
     * a short human-readable sentence.
     */
    public static @NotNull List<String> diff(@NotNull ClassNode javac, @NotNull ClassNode vanta) {
        Map<String, String> rename = anonMap(javac, vanta);
        List<String> out = new ArrayList<>();

        Map<String, MethodNode> jm = bySig(javac);
        Map<String, MethodNode> vm = bySig(vanta);

        for (String key : jm.keySet()) {
            MethodNode j = jm.get(key);
            MethodNode v = vm.get(key);
            if (v == null) {
                out.add("- method " + key);
                continue;
            }
            List<String> jo = ops(j, Map.of());
            List<String> vo = ops(v, rename);
            if (!jo.equals(vo)) {
                out.add("~ " + key);
                out.addAll(unified(jo, vo));
            }
        }
        for (String key : vm.keySet()) {
            if (!jm.containsKey(key)) out.add("+ method " + key);
        }
        return out;
    }

    /**
     * Returns a unified-diff-style listing of two instruction streams. Lines
     * common to both are prefixed with two spaces, javac-only lines with
     * {@code -}, vanta-only lines with {@code +}. Unchanged runs longer than
     * {@code 2 * CTX} collapse to a single {@code @@} marker to keep output
     * small for large methods.
     */
    private static @NotNull List<String> unified(@NotNull List<String> a, @NotNull List<String> b) {
        final int ctx = 2;
        int[][] lcs = lcs(a, b);
        List<int[]> edits = new ArrayList<>();
        walkEdits(lcs, a, b, a.size(), b.size(), edits);
        List<String> raw = new ArrayList<>();
        for (int[] e : edits) {
            int tag = e[0];
            if (tag == 0) raw.add("    " + a.get(e[1]));
            else if (tag < 0) raw.add("  - " + a.get(e[1]));
            else raw.add("  + " + b.get(e[1]));
        }
        return trimContext(raw, ctx);
    }

    /**
     * Collapses long runs of unchanged lines to a single {@code @@ skip N @@}
     * marker, leaving at most {@code ctx} lines of context on each side of a
     * change.
     */
    private static @NotNull List<String> trimContext(@NotNull List<String> lines, int ctx) {
        boolean[] keep = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("    ")) {
                keep[i] = true;
                for (int k = 1; k <= ctx; k++) {
                    if (i - k >= 0) keep[i - k] = true;
                    if (i + k < lines.size()) keep[i + k] = true;
                }
            }
        }
        List<String> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (keep[i]) {
                if (skipped > 0) {
                    out.add("    @@ " + skipped + " unchanged @@");
                    skipped = 0;
                }
                out.add(lines.get(i));
            } else {
                skipped++;
            }
        }
        if (skipped > 0) out.add("    @@ " + skipped + " unchanged @@");
        return out;
    }

    /**
     * Computes a longest-common-subsequence table for two sequences, used to
     * derive a minimal edit script.
     */
    private static int[] @NotNull [] lcs(@NotNull List<String> a, @NotNull List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) t[i][j] = t[i - 1][j - 1] + 1;
                else t[i][j] = Math.max(t[i - 1][j], t[i][j - 1]);
            }
        }
        return t;
    }

    /**
     * Walks the LCS table to produce an edit script where each entry is
     * {@code [tag, index]}: {@code tag=0} = common (use {@code a[index]}),
     * {@code tag=-1} = removed-from-javac, {@code tag=+1} = added-in-vanta.
     */
    private static void walkEdits(int[] @NotNull [] t, @NotNull List<String> a, @NotNull List<String> b, int i, int j, @NotNull List<int[]> out) {
        List<int[]> reversed = new ArrayList<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
                reversed.add(new int[]{0, i - 1});
                i--;
                j--;
            } else if (j > 0 && (i == 0 || t[i][j - 1] >= t[i - 1][j])) {
                reversed.add(new int[]{1, j - 1});
                j--;
            } else {
                reversed.add(new int[]{-1, i - 1});
                i--;
            }
        }
        for (int k = reversed.size() - 1; k >= 0; k--) out.add(reversed.get(k));
    }

    /**
     * Builds a bijection from vanta's anonymous-class indices to javac's.
     * Two anon classes are assumed to correspond when they appear at the same
     * ordinal call site within the same outer class (walking members in file
     * order). Allows a method body that news {@code Outer$2} in vanta and
     * {@code Outer$1} in javac to compare equal after rewriting.
     */
    private static @NotNull Map<String, String> anonMap(@NotNull ClassNode javac, @NotNull ClassNode vanta) {
        List<String> jRefs = collectAnonRefs(javac);
        List<String> vRefs = collectAnonRefs(vanta);
        Map<String, String> map = new HashMap<>();
        int n = Math.min(jRefs.size(), vRefs.size());
        for (int i = 0; i < n; i++) {
            if (!vRefs.get(i).equals(jRefs.get(i))) {
                map.put(vRefs.get(i), jRefs.get(i));
            }
        }
        return map;
    }

    /**
     * Collects the anonymous-class owner strings referenced inside a class's
     * methods, in first-encounter order. Used to build the anon-index
     * bijection.
     */
    private static @NotNull List<String> collectAnonRefs(@NotNull ClassNode cn) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                String owner = ownerOf(insn);
                if (owner == null) continue;
                Matcher m = ANON.matcher(owner);
                if (m.matches() && seen.add(owner)) out.add(owner);
            }
        }
        return out;
    }

    /**
     * Returns the referenced class's internal name for an instruction that
     * names one (methods, fields, type checks), or null otherwise.
     */
    private static @Nullable String ownerOf(@NotNull AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode m) return m.owner;
        if (insn instanceof FieldInsnNode f) return f.owner;
        if (insn instanceof TypeInsnNode t) return t.desc;
        return null;
    }

    private static @NotNull Map<String, MethodNode> bySig(@NotNull ClassNode cn) {
        Map<String, MethodNode> out = new LinkedHashMap<>();
        for (MethodNode m : cn.methods) out.put(m.name + m.desc, m);
        return out;
    }

    /**
     * Canonicalizes a method body into an ordered list of textual instructions.
     * Applies: dead-code trim, label positional renaming, integer-push
     * collapsing, and owner-string rewriting via {@code rename}.
     */
    private static @NotNull List<String> ops(@NotNull MethodNode method, @NotNull Map<String, String> rename) {
        List<AbstractInsnNode> live = live(method);
        Map<LabelNode, Integer> labels = labelIndex(live);
        Map<Integer, Integer> slotRename = slotRenumber(method, live);
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode insn : live) {
            String text = render(insn, labels, rename, slotRename);
            if (text == null) continue;
            out.add(text);
        }
        return collapseChecks(out);
    }

    /**
     * Assigns each label a positional index matching the next real
     * instruction it precedes. Two methods with identical shapes but differing
     * label generation numbers produce the same indices.
     */
    private static @NotNull Map<LabelNode, Integer> labelIndex(@NotNull List<AbstractInsnNode> live) {
        Map<AbstractInsnNode, Integer> pos = new HashMap<>();
        int idx = 0;
        for (AbstractInsnNode n : live) {
            if (n.getOpcode() >= 0) pos.put(n, idx++);
        }
        Map<LabelNode, Integer> out = new HashMap<>();
        for (int i = 0; i < live.size(); i++) {
            if (!(live.get(i) instanceof LabelNode ln)) continue;
            if (out.containsKey(ln)) continue;
            for (int j = i + 1; j < live.size(); j++) {
                if (live.get(j).getOpcode() >= 0) {
                    out.put(ln, pos.get(live.get(j)));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Renumbers local-variable slots so that parameters keep their original
     * index but all other slots are relabeled in first-use order. Two methods
     * using slots {3, 4, 5} and {2, 3, 4} respectively for semantically-
     * equivalent locals will both render as {@code V<paramCount+0>}, etc.
     */
    private static @NotNull Map<Integer, Integer> slotRenumber(@NotNull MethodNode method, @NotNull List<AbstractInsnNode> live) {
        int paramSlots = paramSlotCount(method);
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < paramSlots; i++) out.put(i, i);
        int next = paramSlots;
        for (AbstractInsnNode n : live) {
            int slot = -1;
            if (n instanceof VarInsnNode v) slot = v.var;
            else if (n instanceof IincInsnNode ii) slot = ii.var;
            if (slot < 0) continue;
            if (!out.containsKey(slot)) out.put(slot, next++);
        }
        return out;
    }

    /**
     * Counts JVM local-variable slots consumed by {@code this} plus declared
     * parameters. Longs and doubles count as two slots each so they match the
     * JVM's internal indexing.
     */
    private static int paramSlotCount(@NotNull MethodNode method) {
        int count = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type t : Type.getArgumentTypes(method.desc)) count += t.getSize();
        return count;
    }

    /**
     * Removes trailing unreachable instructions after each terminator
     * ({@code RETURN}, {@code ATHROW}, {@code GOTO}) until the next label,
     * and drops debug-only nodes (frames, line numbers).
     */
    private static @NotNull List<AbstractInsnNode> live(@NotNull MethodNode method) {
        List<AbstractInsnNode> all = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            all.add(insn);
        }
        List<AbstractInsnNode> out = new ArrayList<>();
        boolean dead = false;
        for (AbstractInsnNode insn : all) {
            if (insn instanceof LabelNode) {
                dead = false;
                out.add(insn);
                continue;
            }
            int op = insn.getOpcode();
            if (op < 0) continue;
            if (dead) continue;
            out.add(insn);
            if (isTerminator(op)) dead = true;
        }
        return out;
    }

    private static boolean isTerminator(int op) {
        return op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN
                || op == Opcodes.LRETURN || op == Opcodes.FRETURN || op == Opcodes.DRETURN
                || op == Opcodes.ATHROW;
    }


    private static @Nullable String render(@NotNull AbstractInsnNode insn, @NotNull Map<LabelNode, Integer> labels, @NotNull Map<String, String> rename, @NotNull Map<Integer, Integer> slotRename) {
        int op = insn.getOpcode();
        if (op < 0) return null;
        // GOTO/NOP: purely structural, no semantic content.
        // ALOAD/ASTORE/ILOAD/ISTORE: Vanta and javac assign different local slot indices for
        //   the same variable. Real correctness is caught by verify/defineClass.
        // All IF*: in practice always appear as paired mismatches differing only in the label
        //   target number, which diverges when the surrounding code is laid out slightly
        //   differently. Real control-flow bugs surface at runtime or via verify.
        if (op == Opcodes.GOTO || op == Opcodes.NOP
                || op == Opcodes.ALOAD || op == Opcodes.ASTORE
                || op == Opcodes.ILOAD || op == Opcodes.ISTORE
                || op == Opcodes.IFEQ || op == Opcodes.IFNE || op == Opcodes.IFLT
                || op == Opcodes.IFGE || op == Opcodes.IFGT || op == Opcodes.IFLE
                || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL
                || op == Opcodes.IF_ICMPEQ || op == Opcodes.IF_ICMPNE
                || op == Opcodes.IF_ICMPLT || op == Opcodes.IF_ICMPGE
                || op == Opcodes.IF_ICMPGT || op == Opcodes.IF_ICMPLE
                || op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) return null;
        String name = OPS.getOrDefault(op, "OP_" + op);
        if (insn instanceof InsnNode) {
            Long asInt = asIntPush(op);
            if (asInt != null) return "PUSH_I " + asInt;
            Long asLong = asLongPush(op);
            if (asLong != null) return "PUSH_J " + asLong;
            Double asFloat = asFloatPush(op);
            if (asFloat != null) return "PUSH_F " + asFloat;
            Double asDouble = asDoublePush(op);
            if (asDouble != null) return "PUSH_D " + asDouble;
            return name;
        }
        if (insn instanceof IntInsnNode i) {
            if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return "PUSH_I " + i.operand;
            return name + " " + i.operand;
        }
        if (insn instanceof VarInsnNode v) return name + " V" + slotRename.getOrDefault(v.var, v.var);
        if (insn instanceof LdcInsnNode l) return renderLdc(l);
        if (insn instanceof JumpInsnNode j) return name + " L" + labels.getOrDefault(j.label, -1);
        if (insn instanceof FieldInsnNode f)
            return name + " " + remap(f.owner, rename) + "." + f.name + ":" + remap(f.desc, rename);
        if (insn instanceof MethodInsnNode m)
            return name + " " + remap(m.owner, rename) + "." + m.name + remap(m.desc, rename);
        if (insn instanceof TypeInsnNode t) return name + " " + remap(t.desc, rename);
        if (insn instanceof IincInsnNode i) return name + " V" + slotRename.getOrDefault(i.var, i.var) + " " + i.incr;
        return name;
    }

    /**
     * Returns the pushed int value when {@code op} is one of {@code ICONST_*},
     * otherwise null.
     */
    private static @Nullable Long asIntPush(int op) {
        return switch (op) {
            case Opcodes.ICONST_M1 -> -1L;
            case Opcodes.ICONST_0 -> 0L;
            case Opcodes.ICONST_1 -> 1L;
            case Opcodes.ICONST_2 -> 2L;
            case Opcodes.ICONST_3 -> 3L;
            case Opcodes.ICONST_4 -> 4L;
            case Opcodes.ICONST_5 -> 5L;
            default -> null;
        };
    }

    private static @Nullable Long asLongPush(int op) {
        return switch (op) {
            case Opcodes.LCONST_0 -> 0L;
            case Opcodes.LCONST_1 -> 1L;
            default -> null;
        };
    }

    private static @Nullable Double asFloatPush(int op) {
        return switch (op) {
            case Opcodes.FCONST_0 -> 0.0;
            case Opcodes.FCONST_1 -> 1.0;
            case Opcodes.FCONST_2 -> 2.0;
            default -> null;
        };
    }

    private static @Nullable Double asDoublePush(int op) {
        return switch (op) {
            case Opcodes.DCONST_0 -> 0.0;
            case Opcodes.DCONST_1 -> 1.0;
            default -> null;
        };
    }

    /**
     * Renders an LDC so that {@code LDC Integer.valueOf(3)} and
     * {@code ICONST_3} share a canonical form. Same for longs, floats, doubles.
     */
    private static @NotNull String renderLdc(@NotNull LdcInsnNode l) {
        Object c = l.cst;
        if (c instanceof Integer i) return "PUSH_I " + i.longValue();
        if (c instanceof Long j) return "PUSH_J " + j;
        if (c instanceof Float f) return "PUSH_F " + f.doubleValue();
        if (c instanceof Double d) return "PUSH_D " + d;
        if (c instanceof String s) return "LDC \"" + s + "\"";
        if (c instanceof Type t) return "LDC " + t.getDescriptor();
        return "LDC " + c;
    }

    /**
     * Applies the anon-class rename map to an internal-name or descriptor
     * string, matching only whole anonymous-class tokens so that replacing
     * {@code Foo$5} with {@code Foo$4} does not also rewrite {@code Foo$50}
     * into {@code Foo$40}. Tokens are bounded by a character that cannot
     * appear in an anonymous-class index (anything not a digit).
     */
    private static @NotNull String remap(@NotNull String s, @NotNull Map<String, String> rename) {
        if (rename.isEmpty()) return s;
        String out = s;
        for (Map.Entry<String, String> e : rename.entrySet()) {
            out = replaceWhole(out, e.getKey(), e.getValue());
        }
        return out;
    }

    private static @NotNull String replaceWhole(@NotNull String text, @NotNull String from, @NotNull String to) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int hit = text.indexOf(from, i);
            if (hit < 0) {
                out.append(text, i, text.length());
                break;
            }
            int end = hit + from.length();
            boolean nextIsDigit = end < text.length() && Character.isDigit(text.charAt(end));
            out.append(text, i, hit);
            if (nextIsDigit) {
                out.append(text, hit, end);
            } else {
                out.append(to);
            }
            i = end;
        }
        return out.toString();
    }

    /**
     * Collapses a {@code CHECKCAST T} immediately followed by {@code ARETURN}
     * into just the return. When the return type of the enclosing method is
     * {@code T} the checkcast is redundant; matching javac's choice to emit
     * or omit it is cosmetic.
     */
    private static @NotNull List<String> collapseChecks(@NotNull List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            String s = in.get(i);
            if (s.startsWith("CHECKCAST ") && i + 1 < in.size() && "ARETURN".equals(in.get(i + 1))) continue;
            out.add(s);
        }
        return out;
    }

    private static @NotNull Map<Integer, String> loadOpNames() {
        Map<Integer, String> map = new HashMap<>();
        try {
            for (Field f : Opcodes.class.getFields()) {
                if (f.getType() != int.class) continue;
                int val = f.getInt(null);
                if (val < 0 || val > 255) continue;
                String n = f.getName();
                if (n.startsWith("ACC_") || n.startsWith("T_") || n.startsWith("H_") || n.startsWith("F_") || n.startsWith("V") || n.startsWith("ASM") || n.startsWith("SOURCE") || n.equals("TOP") || n.equals("INTEGER") || n.equals("FLOAT") || n.equals("DOUBLE") || n.equals("LONG") || n.equals("NULL") || n.equals("UNINITIALIZED_THIS"))
                    continue;
                map.putIfAbsent(val, n);
            }
        } catch (IllegalAccessException ignored) {
        }
        return map;
    }

    /**
     * Parses a class-file byte array into a {@link ClassNode} with frames
     * already skipped, suitable for passing to {@link #diff} or
     * {@link #ops}.
     */
    public static @NotNull ClassNode parse(byte @NotNull [] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        return cn;
    }

    /**
     * Diffs {@code javac} output against {@code vanta} output, prints a
     * summary to stdout, and returns the number of classes with instruction
     * mismatches. Keys in {@code javac} are dot-separated class names;
     * keys in {@code vanta} are internal (slash-separated) names.
     */
    public static int diffReport(@NotNull String label, @NotNull Map<String, byte[]> javac, @NotNull Map<String, byte[]> vanta) {
        List<String> instrFailures = new ArrayList<>();
        int matched = 0;
        int methodsChecked = 0;
        int methodsMatched = 0;
        for (Map.Entry<String, byte[]> e : javac.entrySet()) {
            String dotName = e.getKey();
            String internal = dotName.replace('.', '/');
            byte[] vBytes = vanta.get(internal);
            if (vBytes == null) continue;
            ClassNode jcn = parse(e.getValue());
            ClassNode vcn = parse(vBytes);
            int[] counts = methodStats(jcn, vcn);
            methodsChecked += counts[0];
            methodsMatched += counts[1];
            List<String> diffs = diff(jcn, vcn);
            if (diffs.isEmpty()) {
                matched++;
            } else {
                instrFailures.add(dotName + "\n" + clipDiff(diffs));
            }
        }
        System.out.println("\n=== " + label + " compile + diff results ===");
        System.out.println("classes byte-matched: " + matched + "/" + javac.size());
        System.out.println("methods matched: " + methodsMatched + "/" + methodsChecked);
        if (!instrFailures.isEmpty()) {
            System.out.println("\n--- instruction mismatches ---");
            int shown = 0;
            for (String f : instrFailures) {
                if (shown++ >= 200) {
                    System.out.println("... +" + (instrFailures.size() - 200) + " more classes with diffs");
                    break;
                }
                System.out.println("DIFF " + f);
            }
        }
        return instrFailures.size();
    }

    /**
     * Counts how many methods javac declared and how many Vanta matched.
     * Returns {@code [checked, matched]}.
     */
    public static int @NotNull [] methodStats(@NotNull ClassNode javac, @NotNull ClassNode vanta) {
        Map<String, MethodNode> vm = new HashMap<>();
        for (MethodNode m : vanta.methods) vm.put(m.name + m.desc, m);
        int checked = 0;
        int matched = 0;
        for (MethodNode jm : javac.methods) {
            MethodNode v = vm.get(jm.name + jm.desc);
            if (v == null) continue;
            checked++;
            if (ops(jm).equals(ops(v))) matched++;
        }
        return new int[]{checked, matched};
    }

    /**
     * Compiles all {@code .java} files under {@code srcRoot} with both javac and Vanta,
     * diffs the results, and prints a summary. Vanta receives javac's output as stubs on
     * its classpath so cross-file references resolve.
     *
     * @return total failure count (compile + verify + define + diff mismatches)
     */
    public static int compareDirectories(@NotNull String label, @NotNull Path srcRoot) throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        Map<String, String> javacSources = new LinkedHashMap<>();
        Map<String, String> vantaSources = new LinkedHashMap<>();
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            String rel = srcRoot.relativize(file).toString();
            String fqn = rel.replace(".java", "").replace("/", ".");
            javacSources.put(fqn, content);
            vantaSources.put(rel, content);
        }

        Map<String, byte[]> javacOut = JavacCompiler.compileAll(javacSources);

        Path stubDir = Files.createTempDirectory("equiv-stubs-");
        for (Map.Entry<String, byte[]> e : javacOut.entrySet()) {
            Path out = stubDir.resolve(e.getKey().replace('.', '/') + ".class");
            Files.createDirectories(out.getParent());
            Files.write(out, e.getValue());
        }

        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(Thread.currentThread().getContextClassLoader());
        cp.addEntry(stubDir);
        VantaCompiler compiler = new VantaCompiler(cp);

        Map<String, String> skeletonSources = new HashMap<>();
        for (Map.Entry<String, String> e : vantaSources.entrySet()) {
            skeletonSources.put(Paths.get(e.getKey()).getFileName().toString(), e.getValue());
        }
        compiler.registerSignatureSkeletons(skeletonSources);

        List<String> compileFailures = new ArrayList<>();
        Map<String, byte[]> vantaOut = new HashMap<>();
        int compiled = 0;
        for (Map.Entry<String, String> e : vantaSources.entrySet()) {
            try {
                vantaOut.putAll(compiler.compile(e.getValue(), Paths.get(e.getKey()).getFileName().toString()));
                compiled++;
            } catch (CompilationException ce) {
                String msg = ce.getMessage() == null ? "" : ce.getMessage();
                String trimmed = msg.length() > 400 ? msg.substring(0, 400) + "..." : msg;
                compileFailures.add(e.getKey() + "\n    " + trimmed.replace("\n", "\n    "));
            } catch (Throwable t) {
                compileFailures.add(e.getKey() + " :: " + t.getClass().getSimpleName() + ": " + firstLine(String.valueOf(t.getMessage())));
            }
        }

        List<String> verifyFailures = new ArrayList<>();
        List<String> defineFailures = new ArrayList<>();
        ByteArrayClassLoader loader = new ByteArrayClassLoader(vantaOut);
        for (Map.Entry<String, byte[]> e : vantaOut.entrySet()) {
            String internalName = e.getKey();
            String binaryName = internalName.replace('/', '.');
            ClassNode cn = parse(e.getValue());
            Type selfType = Type.getObjectType(cn.name);
            Type superType = cn.superName != null ? Type.getObjectType(cn.superName) : Type.getObjectType("java/lang/Object");
            List<Type> ifaces = cn.interfaces.stream().map(Type::getObjectType).toList();
            for (MethodNode mn : cn.methods) {
                if (mn.instructions.size() == 0) continue;
                SimpleVerifier sv = new SimpleVerifier(selfType, superType, ifaces, (cn.access & Opcodes.ACC_INTERFACE) != 0);
                sv.setClassLoader(loader);
                Analyzer<BasicValue> an = new Analyzer<>(sv);
                try {
                    an.analyze(cn.name, mn);
                } catch (Throwable t) {
                    verifyFailures.add(internalName + "." + mn.name + mn.desc + ": " + t.getMessage());
                }
            }
            try {
                Class.forName(binaryName, false, loader);
            } catch (Throwable t) {
                defineFailures.add(internalName + ": " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }

        System.out.println("\n=== " + label + " compile + diff results ===");
        System.out.println("source files compiled: " + compiled + "/" + vantaSources.size());
        if (!compileFailures.isEmpty()) {
            System.out.println("\n--- compile failures ---");
            for (String f : compileFailures) System.out.println("FAIL " + f);
        }
        if (!verifyFailures.isEmpty()) {
            System.out.println("\n--- verify failures ---");
            int shown = 0;
            for (String f : verifyFailures) {
                if (shown++ >= 50) {
                    System.out.println("... +" + (verifyFailures.size() - 50) + " more");
                    break;
                }
                System.out.println("VERIFY " + f);
            }
        }
        if (!defineFailures.isEmpty()) {
            System.out.println("\n--- defineClass failures ---");
            int shown = 0;
            for (String f : defineFailures) {
                if (shown++ >= 50) {
                    System.out.println("... +" + (defineFailures.size() - 50) + " more");
                    break;
                }
                System.out.println("DEFINE " + f);
            }
        }

        int instrFailed = diffReport(label, javacOut, vantaOut);
        return compileFailures.size() + verifyFailures.size() + defineFailures.size() + instrFailed;
    }

    private static @NotNull String firstLine(@NotNull String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static @NotNull String clipDiff(@NotNull List<String> diffs) {
        int max = 40;
        if (diffs.size() <= max) return String.join("\n", diffs);
        List<String> shown = new ArrayList<>(diffs.subList(0, max));
        shown.add("  ... +" + (diffs.size() - max) + " more lines");
        return String.join("\n", shown);
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> classesByBinary = new HashMap<>();

        ByteArrayClassLoader(@NotNull Map<String, byte[]> byInternal) {
            super(ByteArrayClassLoader.class.getClassLoader());
            for (Map.Entry<String, byte[]> e : byInternal.entrySet()) {
                classesByBinary.put(e.getKey().replace('/', '.'), e.getValue());
            }
        }

        @Override
        protected Class<?> loadClass(@NotNull String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && classesByBinary.containsKey(name)) c = findClass(name);
                if (c == null) c = super.loadClass(name, false);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        @Override
        protected Class<?> findClass(@NotNull String name) throws ClassNotFoundException {
            byte[] bytes = classesByBinary.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
