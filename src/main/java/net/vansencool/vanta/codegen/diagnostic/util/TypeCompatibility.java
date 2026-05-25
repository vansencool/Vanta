package net.vansencool.vanta.codegen.diagnostic.util;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;

/**
 * Assignability checks between a target {@link ResolvedType} and a source expression's inferred type.
 */
public final class TypeCompatibility {

    private TypeCompatibility() {
    }

    /**
     * @param exprGen   expression generator (for super chain walks)
     * @param target    declared destination type
     * @param actual    inferred source expression type, may be null when inference failed
     * @param source    source expression, used to detect int literal narrowing
     * @return true when {@code actual} can be assigned to {@code target} without an explicit cast
     */
    public static boolean assignable(@NotNull ExpressionGenerator exprGen, @NotNull ResolvedType target, @Nullable ResolvedType actual, @NotNull Expression source) {
        if (actual == null) return true;
        if (target.descriptor().equals(actual.descriptor())) return true;
        if (actual == ResolvedType.NULL) return !target.isPrimitive();
        if (target.isPrimitive() && actual.isPrimitive()) return primitiveAssignable(target.descriptor(), actual.descriptor(), source);
        if (target.isPrimitive() && !actual.isPrimitive()) return unboxAssignable(target.descriptor(), actual);
        if (!target.isPrimitive() && actual.isPrimitive()) return boxAssignable(target, actual.descriptor());
        if (target.descriptor().startsWith("[") && actual.descriptor().startsWith("[")) return arrayAssignable(exprGen, target, actual);
        return referenceAssignable(exprGen, target, actual);
    }

    private static boolean arrayAssignable(@NotNull ExpressionGenerator exprGen, @NotNull ResolvedType target, @NotNull ResolvedType actual) {
        String t = target.descriptor();
        String a = actual.descriptor();
        if (t.equals(a)) return true;
        int td = 0;
        int ad = 0;
        while (td < t.length() && t.charAt(td) == '[') td++;
        while (ad < a.length() && a.charAt(ad) == '[') ad++;
        if (td != ad) return false;
        String tElem = t.substring(td);
        String aElem = a.substring(ad);
        if (tElem.equals(aElem)) return true;
        if (tElem.startsWith("L") && aElem.startsWith("L")) {
            String tInternal = tElem.substring(1, tElem.length() - 1);
            String aInternal = aElem.substring(1, aElem.length() - 1);
            if ("java/lang/Object".equals(aInternal)) return true;
            return exprGen.isSubtype(aInternal, tInternal, new HashSet<>());
        }
        return false;
    }

    private static boolean primitiveAssignable(@NotNull String targetDesc, @NotNull String actualDesc, @NotNull Expression source) {
        if (targetDesc.equals(actualDesc)) return true;
        int t = primRank(targetDesc);
        int a = primRank(actualDesc);
        if (t < 0 || a < 0) return false;
        if (t >= a && !crossesIntegralFloatLine(actualDesc, targetDesc)) return true;
        if (source instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL) {
            return intLiteralFitsNarrowTarget(targetDesc, lit);
        }
        return false;
    }

    private static int primRank(@NotNull String desc) {
        return switch (desc) {
            case "Z" -> 0;
            case "B" -> 1;
            case "S" -> 2;
            case "C" -> 2;
            case "I" -> 3;
            case "J" -> 4;
            case "F" -> 5;
            case "D" -> 6;
            default -> -1;
        };
    }

    private static boolean crossesIntegralFloatLine(@NotNull String actual, @NotNull String target) {
        boolean actualFloating = "F".equals(actual) || "D".equals(actual);
        boolean targetFloating = "F".equals(target) || "D".equals(target);
        return actualFloating && !targetFloating;
    }

    private static boolean intLiteralFitsNarrowTarget(@NotNull String targetDesc, @NotNull LiteralExpression lit) {
        try {
            long value = Long.parseLong(lit.value().replace("_", ""));
            return switch (targetDesc) {
                case "B" -> value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
                case "S" -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
                case "C" -> value >= Character.MIN_VALUE && value <= Character.MAX_VALUE;
                default -> false;
            };
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean unboxAssignable(@NotNull String targetPrimDesc, @NotNull ResolvedType actualBoxed) {
        if (actualBoxed.internalName() == null) return false;
        String boxedPrim = wrapperPrimitiveDesc(actualBoxed.internalName());
        if (boxedPrim == null) return false;
        return primitiveAssignable(targetPrimDesc, boxedPrim, dummy());
    }

    private static boolean boxAssignable(@NotNull ResolvedType target, @NotNull String actualPrimDesc) {
        if (target.internalName() == null) return false;
        if ("java/lang/Object".equals(target.internalName())) return true;
        if (isWrapperSuper(target.internalName(), actualPrimDesc)) return true;
        String targetPrim = wrapperPrimitiveDesc(target.internalName());
        return targetPrim != null && targetPrim.equals(actualPrimDesc);
    }

    private static boolean isWrapperSuper(@NotNull String targetInternal, @NotNull String actualPrimDesc) {
        boolean numeric = "B".equals(actualPrimDesc) || "S".equals(actualPrimDesc) || "C".equals(actualPrimDesc) || "I".equals(actualPrimDesc) || "J".equals(actualPrimDesc) || "F".equals(actualPrimDesc) || "D".equals(actualPrimDesc);
        if (numeric && ("java/lang/Number".equals(targetInternal) || "java/lang/Comparable".equals(targetInternal) || "java/io/Serializable".equals(targetInternal))) return true;
        if ("Z".equals(actualPrimDesc) && ("java/lang/Comparable".equals(targetInternal) || "java/io/Serializable".equals(targetInternal))) return true;
        return false;
    }

    private static boolean referenceAssignable(@NotNull ExpressionGenerator exprGen, @NotNull ResolvedType target, @NotNull ResolvedType actual) {
        if (target.internalName() == null || actual.internalName() == null) {
            return target.descriptor().equals(actual.descriptor()) || "Ljava/lang/Object;".equals(target.descriptor()) || "Ljava/lang/Object;".equals(actual.descriptor());
        }
        if ("java/lang/Object".equals(target.internalName()) || "java/lang/Object".equals(actual.internalName())) return true;
        if (target.internalName().equals(actual.internalName())) return true;
        if (exprGen.isSubtype(actual.internalName(), target.internalName(), new HashSet<>())) return true;
        return exprGen.isSubtype(target.internalName(), actual.internalName(), new HashSet<>());
    }

    /**
     * @return primitive descriptor backing a boxed wrapper internal name, or null when {@code internalName} is not a recognised wrapper
     */
    public static @Nullable String wrapperPrimitiveDesc(@NotNull String internalName) {
        return switch (internalName) {
            case "java/lang/Boolean" -> "Z";
            case "java/lang/Byte" -> "B";
            case "java/lang/Short" -> "S";
            case "java/lang/Character" -> "C";
            case "java/lang/Integer" -> "I";
            case "java/lang/Long" -> "J";
            case "java/lang/Float" -> "F";
            case "java/lang/Double" -> "D";
            default -> null;
        };
    }

    /**
     * @return human readable name for {@code type} suitable for diagnostic text
     */
    public static @NotNull String display(@NotNull ResolvedType type) {
        if (type == ResolvedType.NULL) return "null";
        if (type.internalName() != null) return type.internalName().replace('/', '.');
        return primitiveName(type.descriptor());
    }

    private static @NotNull String primitiveName(@NotNull String desc) {
        return switch (desc) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "S" -> "short";
            case "C" -> "char";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            case "V" -> "void";
            default -> desc;
        };
    }

    private static @NotNull Expression dummy() {
        return DUMMY;
    }

    private static final Expression DUMMY = new LiteralExpression(TokenType.NULL, "null", 0);
}
