package net.vansencool.vanta.codegen.diagnostic.typecheck;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.TypeCompatibility;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Classifies an assignment type mismatch and produces the descriptive pieces of a diagnostic.
 *
 * @param kind         classification used to pick rendering branches
 * @param mainTitle    one line description of the mismatch
 * @param mainLabel    underline label shown on the highlighted span
 * @param note         long form explanation of why the assignment fails
 * @param helpLines    suggested fixes the user can try, in priority order
 * @param closestSuper closest common reference supertype between target and actual, when relevant
 */
public record MismatchAnalysis(@NotNull MismatchKind kind, @NotNull String mainTitle, @NotNull String mainLabel,
                               @NotNull String note, @NotNull List<String> helpLines,
                               @Nullable String closestSuper) {

    /**
     * @param ctx        method context being compiled
     * @param exprGen    expression generator (for super chain walks)
     * @param targetName what the target slot is (e.g. {@code "variable 'x'"})
     * @param target     declared destination type
     * @param actual     inferred source type, null when inference failed
     * @param value      source expression on the right hand side
     * @return mismatch analysis ready for rendering
     */
    public static @NotNull MismatchAnalysis classify(@NotNull MethodContext ctx, @NotNull ExpressionGenerator exprGen, @NotNull String targetName, @NotNull ResolvedType target, @Nullable ResolvedType actual, @NotNull Expression value) {
        String targetDisplay = TypeCompatibility.display(target);
        if (actual == null) {
            return new MismatchAnalysis(
                    MismatchKind.UNKNOWN,
                    "type of right hand side could not be inferred for assignment to " + targetName,
                    "expression has no known type",
                    targetName + " was declared as " + targetDisplay + ", but the right hand side did not resolve to any concrete type so the assignment cannot be checked",
                    List.of("ensure the right hand side resolves to a value of type " + targetDisplay + " before this assignment"),
                    null);
        }
        String actualDisplay = TypeCompatibility.display(actual);
        if ("V".equals(target.descriptor())) {
            return new MismatchAnalysis(
                    MismatchKind.VOID_RESULT,
                    "void method cannot return a value",
                    "this method has no return type, the value here is discarded by void",
                    targetName + " is void, void methods may only use 'return;' without a value",
                    List.of("drop the returned expression: 'return;'", "change the method's return type to " + actualDisplay + " if returning the value is intended"),
                    null);
        }
        if ("V".equals(actual.descriptor())) {
            return new MismatchAnalysis(
                    MismatchKind.VOID_RESULT,
                    "cannot assign the void result of an expression to " + targetName,
                    "this expression has no value (void)",
                    targetName + " was declared as " + targetDisplay + ", but the right hand side is a void method call which produces no value",
                    List.of("call the method on its own as a statement, or change it to return " + targetDisplay),
                    null);
        }
        if (target.isPrimitive() && actual == ResolvedType.NULL) {
            return new MismatchAnalysis(
                    MismatchKind.NULL_INTO_PRIMITIVE,
                    "cannot assign null to primitive " + targetName,
                    "primitive '" + targetDisplay + "' cannot hold null",
                    "primitives have no null value, only reference types can be null",
                    List.of(
                            "declare " + targetName + " using the boxed type '" + boxedFor(target.descriptor()) + "' if it must accept null",
                            "assign a concrete " + targetDisplay + " value instead of null"),
                    null);
        }
        boolean targetIsString = "java/lang/String".equals(target.internalName());
        boolean actualIsString = "java/lang/String".equals(actual.internalName());
        if (target.isPrimitive() && actualIsString) {
            return new MismatchAnalysis(
                    MismatchKind.STRING_INTO_PRIMITIVE,
                    "cannot assign String to primitive " + targetName,
                    "String value cannot be stored in a primitive '" + targetDisplay + "' slot",
                    targetName + " was declared as primitive '" + targetDisplay + "', but the right hand side is a String, which has no implicit conversion to a primitive",
                    List.of(parseHelpForPrimitive(target.descriptor()), "declare " + targetName + " as String if it should hold text"),
                    null);
        }
        if (targetIsString && actual.isPrimitive()) {
            return new MismatchAnalysis(
                    MismatchKind.PRIMITIVE_INTO_STRING,
                    "cannot assign primitive " + actualDisplay + " to String " + targetName,
                    "primitive '" + actualDisplay + "' is not a reference, no implicit String conversion",
                    targetName + " was declared as String, but the right hand side is a primitive '" + actualDisplay + "' which is not a reference type",
                    List.of("convert with String.valueOf(<value>)", "use string concatenation: \"\" + <value>"),
                    null);
        }
        if (target.isPrimitive() && actual.isPrimitive()) {
            if (isFloating(actual.descriptor()) && isIntegral(target.descriptor())) {
                return new MismatchAnalysis(
                        MismatchKind.FLOATING_INTO_INTEGRAL,
                        "cannot assign " + actualDisplay + " to integral " + targetName + " without a cast",
                        actualDisplay + " is a floating point value, " + targetDisplay + " is integral",
                        "integral targets reject floating values without an explicit cast since the fractional part would be silently dropped",
                        List.of(
                                "round explicitly: (" + targetDisplay + ") Math.round(<value>)",
                                "truncate explicitly: (" + targetDisplay + ") <value>",
                                "declare " + targetName + " as " + actualDisplay + " if you want the floating value"),
                        null);
            }
            if ("Z".equals(target.descriptor()) ^ "Z".equals(actual.descriptor())) {
                return new MismatchAnalysis(
                        MismatchKind.BOOLEAN_NUMERIC_MIX,
                        "cannot mix boolean and numeric primitives in assignment to " + targetName,
                        "boolean and numeric primitives are unrelated",
                        "Java does not convert between boolean and numeric primitives, they are separate kinds",
                        List.of("derive a boolean explicitly: <value> != 0", "derive a number explicitly: <bool> ? 1 : 0"),
                        null);
            }
            return new MismatchAnalysis(
                    MismatchKind.NARROWING_PRIMITIVE,
                    "narrowing primitive conversion from " + actualDisplay + " to " + targetDisplay + " requires an explicit cast",
                    "narrowing " + actualDisplay + " -> " + targetDisplay + " is not implicit",
                    targetName + " is declared as '" + targetDisplay + "', a narrower range than '" + actualDisplay + "', so an implicit assignment could silently truncate the value",
                    List.of("cast explicitly: (" + targetDisplay + ") <value>", "declare " + targetName + " as " + actualDisplay + " to keep the full range"),
                    null);
        }
        if (target.isPrimitive() && !actual.isPrimitive()) {
            String boxedPrim = actual.internalName() == null ? null : TypeCompatibility.wrapperPrimitiveDesc(actual.internalName());
            if (boxedPrim != null && !boxedPrim.equals(target.descriptor())) {
                return new MismatchAnalysis(
                        MismatchKind.BOXED_INTO_DIFFERENT_PRIMITIVE,
                        "cannot assign boxed " + actualDisplay + " to primitive " + targetDisplay + " " + targetName,
                        "unbox produces '" + primName(boxedPrim) + "', not '" + targetDisplay + "'",
                        "unboxing converts " + actualDisplay + " to its backing primitive '" + primName(boxedPrim) + "', not to '" + targetDisplay + "', assigning that into '" + targetDisplay + "' would also need a narrowing or widening conversion",
                        List.of(
                                "unbox then cast: (" + targetDisplay + ") <value>." + xxxValueFor(boxedPrim) + "()",
                                "declare " + targetName + " as '" + primName(boxedPrim) + "' if you wanted the boxed primitive's value"),
                        null);
            }
            return new MismatchAnalysis(
                    MismatchKind.BOXED_INTO_DIFFERENT_PRIMITIVE,
                    "cannot implicitly unbox " + actualDisplay + " into primitive " + targetDisplay + " " + targetName,
                    "no automatic unboxing from this reference type",
                    targetName + " is primitive '" + targetDisplay + "', but the right hand side is a reference '" + actualDisplay + "' that is not a recognised boxed wrapper",
                    List.of("declare " + targetName + " as a reference type compatible with '" + actualDisplay + "'", "extract a primitive value via an explicit method call"),
                    null);
        }
        if (!target.isPrimitive() && actual.isPrimitive()) {
            String wrapperFor = wrapperInternalFor(actual.descriptor());
            return new MismatchAnalysis(
                    MismatchKind.PRIMITIVE_INTO_INCOMPATIBLE_BOX,
                    "cannot box " + actualDisplay + " into incompatible reference " + targetDisplay + " " + targetName,
                    "primitive '" + actualDisplay + "' boxes to '" + wrapperFor.replace('/', '.') + "', not '" + targetDisplay + "'",
                    "boxing of '" + actualDisplay + "' produces '" + wrapperFor.replace('/', '.') + "', which is not assignable to '" + targetDisplay + "'",
                    List.of(
                            "declare " + targetName + " as '" + wrapperFor.replace('/', '.') + "' or 'java.lang.Object'",
                            "convert explicitly: " + wrapperFor.replace('/', '.') + ".valueOf(<value>)"),
                    null);
        }
        if (isArrayDesc(target.descriptor()) || isArrayDesc(actual.descriptor())) {
            if (arrayDims(target.descriptor()) != arrayDims(actual.descriptor())) {
                int targetDims = arrayDims(target.descriptor());
                int actualDims = arrayDims(actual.descriptor());
                return new MismatchAnalysis(
                        MismatchKind.ARRAY_DIM_MISMATCH,
                        "array dimension mismatch assigning " + actualDisplay + " to " + targetName,
                        "expected " + targetDims + "-dimensional array, got " + actualDims,
                        targetName + " is declared as " + targetDisplay + " (" + targetDims + " dimension" + (targetDims == 1 ? "" : "s") + "), but the right hand side is " + actualDisplay + " (" + actualDims + " dimension" + (actualDims == 1 ? "" : "s") + ")",
                        List.of("adjust array creation to match dimensions: " + targetDisplay, "iterate the source array if you meant to assign an element"),
                        null);
            }
            return new MismatchAnalysis(
                    MismatchKind.ARRAY_ELEMENT_INCOMPATIBLE,
                    "array element type mismatch assigning " + actualDisplay + " to " + targetName,
                    "element types are incompatible",
                    targetName + " is declared as " + targetDisplay + ", but the right hand side's element type is not assignable to the target's element type",
                    List.of("create a new array of " + targetDisplay + " and copy compatible elements", "declare " + targetName + " using the array's actual element type"),
                    null);
        }
        if (target.internalName() != null && actual.internalName() != null) {
            TypeRegistry registry = ctx.methodResolver().classpathManager().typeRegistry();
            TypeSymbol targetSym = registry.lookup(target.internalName());
            TypeSymbol actualSym = registry.lookup(actual.internalName());
            boolean bothEnum = targetSym != null && actualSym != null && targetSym.isEnum() && actualSym.isEnum();
            String common = commonSupertype(exprGen, target.internalName(), actual.internalName());
            List<String> helps = new ArrayList<>();
            if (common != null && !"java/lang/Object".equals(common)) {
                helps.add("declare " + targetName + " using their shared supertype '" + common.replace('/', '.') + "' if both forms must be accepted");
            }
            helps.add("cast explicitly if you know the value is actually a " + targetDisplay + ": (" + targetDisplay + ") <value>");
            helps.add("convert with a builder, factory, or constructor that takes a " + actualDisplay);
            String note = "no widening conversion exists from '" + actualDisplay + "' to '" + targetDisplay + "', the right hand side is neither the declared type nor a subtype of it"
                    + (bothEnum ? ". Both are enum types, which are implicitly final, so no subtype relationship exists between distinct enums" : "");
            return new MismatchAnalysis(
                    bothEnum ? MismatchKind.ENUM_MISMATCH : MismatchKind.UNRELATED_REFERENCES,
                    "incompatible reference types: cannot assign " + actualDisplay + " to " + targetDisplay + " " + targetName,
                    actualDisplay + " is not a subtype of " + targetDisplay,
                    note,
                    helps,
                    common);
        }
        return new MismatchAnalysis(
                MismatchKind.UNKNOWN,
                "type mismatch assigning " + actualDisplay + " to " + targetName,
                "right hand side does not match the declared type",
                targetName + " is declared as " + targetDisplay + ", which the right hand side's type " + actualDisplay + " is not assignable to",
                List.of("either declare " + targetName + " as " + actualDisplay + ", or convert the right hand side to " + targetDisplay),
                null);
    }

    private static boolean isFloating(@NotNull String desc) {
        return "F".equals(desc) || "D".equals(desc);
    }

    private static boolean isIntegral(@NotNull String desc) {
        return "B".equals(desc) || "S".equals(desc) || "C".equals(desc) || "I".equals(desc) || "J".equals(desc);
    }

    private static @NotNull String boxedFor(@NotNull String primDesc) {
        return switch (primDesc) {
            case "Z" -> "Boolean";
            case "B" -> "Byte";
            case "S" -> "Short";
            case "C" -> "Character";
            case "I" -> "Integer";
            case "J" -> "Long";
            case "F" -> "Float";
            case "D" -> "Double";
            default -> primDesc;
        };
    }

    private static @NotNull String wrapperInternalFor(@NotNull String primDesc) {
        return switch (primDesc) {
            case "Z" -> "java/lang/Boolean";
            case "B" -> "java/lang/Byte";
            case "S" -> "java/lang/Short";
            case "C" -> "java/lang/Character";
            case "I" -> "java/lang/Integer";
            case "J" -> "java/lang/Long";
            case "F" -> "java/lang/Float";
            case "D" -> "java/lang/Double";
            default -> "java/lang/Object";
        };
    }

    private static @NotNull String parseHelpForPrimitive(@NotNull String primDesc) {
        return switch (primDesc) {
            case "B" -> "parse explicitly: Byte.parseByte(<value>)";
            case "S" -> "parse explicitly: Short.parseShort(<value>)";
            case "C" -> "extract a char: <value>.charAt(0)";
            case "I" -> "parse explicitly: Integer.parseInt(<value>)";
            case "J" -> "parse explicitly: Long.parseLong(<value>)";
            case "F" -> "parse explicitly: Float.parseFloat(<value>)";
            case "D" -> "parse explicitly: Double.parseDouble(<value>)";
            case "Z" -> "parse explicitly: Boolean.parseBoolean(<value>)";
            default -> "convert the String to a primitive explicitly";
        };
    }

    private static @NotNull String xxxValueFor(@NotNull String primDesc) {
        return switch (primDesc) {
            case "B" -> "byteValue";
            case "S" -> "shortValue";
            case "C" -> "charValue";
            case "I" -> "intValue";
            case "J" -> "longValue";
            case "F" -> "floatValue";
            case "D" -> "doubleValue";
            case "Z" -> "booleanValue";
            default -> "value";
        };
    }

    private static @NotNull String primName(@NotNull String desc) {
        return switch (desc) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "S" -> "short";
            case "C" -> "char";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> desc;
        };
    }

    private static boolean isArrayDesc(@NotNull String desc) {
        return desc.startsWith("[");
    }

    private static int arrayDims(@NotNull String desc) {
        int dims = 0;
        while (dims < desc.length() && desc.charAt(dims) == '[') dims++;
        return dims;
    }

    private static @Nullable String commonSupertype(@NotNull ExpressionGenerator exprGen, @NotNull String a, @NotNull String b) {
        if (a.equals(b)) return a;
        if (exprGen.isSubtype(a, b, new HashSet<>())) return b;
        if (exprGen.isSubtype(b, a, new HashSet<>())) return a;
        return null;
    }
}
