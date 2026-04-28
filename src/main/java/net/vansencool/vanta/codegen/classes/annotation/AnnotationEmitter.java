package net.vansencool.vanta.codegen.classes.annotation;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.parser.ast.declaration.AnnotationNode;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

/**
 * Emits annotation attributes ({@code RuntimeVisibleAnnotations} /
 * {@code RuntimeInvisibleAnnotations}) onto classes, methods, and fields.
 * Walks annotation attribute expressions and translates each source form
 * (literals, array initializers, class literals, enum constants, negated
 * numeric literals) into the corresponding ASM {@link AnnotationVisitor} calls.
 */
public final class AnnotationEmitter {

    private final @NotNull TypeResolver typeResolver;
    private final @NotNull ClasspathManager classpathManager;

    /**
     * Creates an emitter bound to the surrounding resolver and classpath. The
     * resolver is used to translate unqualified annotation/type references to
     * internal names; the classpath reads each annotation's
     * {@link Retention} policy to choose the right attribute kind.
     *
     * @param typeResolver     resolves names to internal descriptors
     * @param classpathManager provides access to external annotation classes
     */
    public AnnotationEmitter(@NotNull TypeResolver typeResolver, @NotNull ClasspathManager classpathManager) {
        this.typeResolver = typeResolver;
        this.classpathManager = classpathManager;
    }

    /**
     * Emits class-level annotations on {@code cw}.
     *
     * @param cw          target class writer
     * @param annotations AST-level annotation nodes to emit
     */
    public void emitClassAnnotations(@NotNull ClassWriter cw, @NotNull List<AnnotationNode> annotations) {
        for (AnnotationNode ann : annotations) {
            String descriptor = annotationDescriptor(ann);
            boolean visible = annotationIsRuntimeVisible(ann);
            AnnotationVisitor av = cw.visitAnnotation(descriptor, visible);
            emitAnnotationValues(av, ann);
            av.visitEnd();
        }
    }

    /**
     * Emits method-level annotations on {@code mv}.
     *
     * @param mv          target method visitor
     * @param annotations AST-level annotation nodes to emit
     */
    public void emitMethodAnnotations(@NotNull MethodVisitor mv, @NotNull List<AnnotationNode> annotations) {
        for (AnnotationNode ann : annotations) {
            String descriptor = annotationDescriptor(ann);
            boolean visible = annotationIsRuntimeVisible(ann);
            AnnotationVisitor av = mv.visitAnnotation(descriptor, visible);
            emitAnnotationValues(av, ann);
            av.visitEnd();
        }
    }

    /**
     * Emits field-level annotations on {@code fv}.
     *
     * @param fv          target field visitor
     * @param annotations AST-level annotation nodes to emit
     */
    public void emitFieldAnnotations(@NotNull FieldVisitor fv, @NotNull List<AnnotationNode> annotations) {
        for (AnnotationNode ann : annotations) {
            String descriptor = annotationDescriptor(ann);
            boolean visible = annotationIsRuntimeVisible(ann);
            AnnotationVisitor av = fv.visitAnnotation(descriptor, visible);
            emitAnnotationValues(av, ann);
            av.visitEnd();
        }
    }

    /**
     * Walks an annotation attribute expression and emits its ASM representation
     * onto {@code av}. Exposed so callers that already hold an
     * {@link AnnotationVisitor} (e.g. annotation-type default values) can
     * reuse the per-value emitter directly.
     *
     * @param av    target annotation visitor
     * @param name  attribute name, or null when inside an array literal
     * @param value attribute expression from source
     */
    public void emitAnnotationValue(@NotNull AnnotationVisitor av, @Nullable String name, @NotNull Expression value) {
        if (value instanceof LiteralExpression lit) {
            Object boxed = literalToObject(lit);
            if (boxed != null) av.visit(name, boxed);
            return;
        }
        if (value instanceof ArrayInitializerExpression arr) {
            AnnotationVisitor sub = av.visitArray(name);
            for (Expression el : arr.elements()) emitAnnotationValue(sub, null, el);
            sub.visitEnd();
            return;
        }
        if (value instanceof FieldAccessExpression fa && "class".equals(fa.fieldName())) {
            String typeName = qualifiedNameOf(fa.target());
            if (typeName != null) {
                TypeNode synth = new TypeNode(typeName, null, 0, fa.line());
                av.visit(name, Type.getType("L" + typeResolver.resolveInternalName(synth) + ";"));
                return;
            }
        }
        if (value instanceof FieldAccessExpression fa) {
            String enumOwner = qualifiedNameOf(fa.target());
            if (enumOwner != null) {
                TypeNode synth = new TypeNode(enumOwner, null, 0, fa.line());
                String enumDesc = "L" + typeResolver.resolveInternalName(synth) + ";";
                av.visitEnum(name, enumDesc, fa.fieldName());
                return;
            }
        }
        if (value instanceof NameExpression ne) {
            Class<?> enumCls = classpathManager.loadClass(typeResolver.resolveInternalName(new TypeNode(ne.name(), null, 0, ne.line())));
            if (enumCls != null && enumCls.isEnum()) {
                av.visitEnum(name, "L" + enumCls.getName().replace('.', '/') + ";", ne.name());
                return;
            }
        }
        if (value instanceof MethodCallExpression call && "class".equals(call.methodName())) {
            return;
        }
        if (value instanceof UnaryExpression un && "-".equals(un.operator()) && un.operand() instanceof LiteralExpression lit) {
            Object boxed = literalToObject(lit);
            if (boxed instanceof Integer i) av.visit(name, -i);
            else if (boxed instanceof Long l) av.visit(name, -l);
            else if (boxed instanceof Float f) av.visit(name, -f);
            else if (boxed instanceof Double d) av.visit(name, -d);
        }
    }

    /**
     * Produces the JVM descriptor for {@code ann}'s type. Dotted names are
     * assumed fully qualified; bare names are resolved via the current
     * {@link TypeResolver}.
     *
     * @param ann annotation node whose type descriptor is needed
     * @return JVM field-style descriptor like {@code "Ljava/lang/Override;"}
     */
    private @NotNull String annotationDescriptor(@NotNull AnnotationNode ann) {
        String name = ann.name();
        if (name.contains(".")) return "L" + name.replace('.', '/') + ";";
        TypeNode synth = new TypeNode(name, null, 0, ann.line());
        return "L" + typeResolver.resolveInternalName(synth) + ";";
    }

    /**
     * Decides whether an annotation should end up in the runtime-visible or
     * runtime-invisible attribute table by reading its {@link Retention}
     * policy. Defaults to runtime-visible when the class can't be loaded.
     *
     * @param ann annotation node to inspect
     * @return true for {@link RetentionPolicy#RUNTIME}, false otherwise
     */
    private boolean annotationIsRuntimeVisible(@NotNull AnnotationNode ann) {
        String desc = annotationDescriptor(ann);
        String internal = desc.substring(1, desc.length() - 1);
        Class<?> c = classpathManager.loadClass(internal);
        if (c == null) return true;
        Retention r = c.getAnnotation(Retention.class);
        if (r == null) return false;
        return r.value() == RetentionPolicy.RUNTIME;
    }

    /**
     * Walks {@code ann}'s attribute map and emits each entry via
     * {@link #emitAnnotationValue(AnnotationVisitor, String, Expression)}.
     *
     * @param av  target annotation visitor
     * @param ann source-level annotation with parsed attribute expressions
     */
    private void emitAnnotationValues(@NotNull AnnotationVisitor av, @NotNull AnnotationNode ann) {
        if (ann.attributes() == null) return;
        for (Map.Entry<String, Expression> e : ann.attributes().entrySet()) {
            emitAnnotationValue(av, e.getKey(), e.getValue());
        }
    }

    /**
     * Flattens a name or chain of field accesses into its dotted source form,
     * returning null when the expression can't be expressed as a simple
     * qualified name (e.g. contains an index or method call).
     *
     * @param expr expression to flatten
     * @return dotted source name or null when the expression isn't a name chain
     */
    private @Nullable String qualifiedNameOf(@NotNull Expression expr) {
        if (expr instanceof NameExpression n) return n.name();
        if (expr instanceof FieldAccessExpression fa) {
            String inner = qualifiedNameOf(fa.target());
            if (inner != null) return inner + "." + fa.fieldName();
        }
        return null;
    }

    /**
     * Converts a literal token into the boxed value ASM's annotation API
     * expects (primitive wrappers, {@link String}).
     *
     * @param lit literal from the parser
     * @return boxed value, or null for literal kinds with no direct mapping
     */
    private @Nullable Object literalToObject(@NotNull LiteralExpression lit) {
        return switch (lit.literalType()) {
            case INT_LITERAL -> LiteralParser.parseIntLiteral(lit.value());
            case LONG_LITERAL -> LiteralParser.parseLongLiteral(lit.value());
            case FLOAT_LITERAL -> Float.parseFloat(lit.value().replace("_", "").replace("f", "").replace("F", ""));
            case DOUBLE_LITERAL -> Double.parseDouble(lit.value().replace("_", "").replace("d", "").replace("D", ""));
            case STRING_LITERAL, TEXT_BLOCK -> unquoteString(lit.value());
            case CHAR_LITERAL -> unquoteChar(lit.value());
            case TRUE -> true;
            case FALSE -> false;
            default -> null;
        };
    }

    /**
     * Strips outer quotes from a string literal and decodes Java escape
     * sequences. Separate from {@link LiteralParser} so annotation emission
     * can keep its narrower, well-tested escape table.
     *
     * @param raw raw string-literal token including quotes
     * @return decoded host-level string
     */
    private @NotNull String unquoteString(@NotNull String raw) {
        String val = raw;
        if (val.startsWith("\"\"\"") && val.endsWith("\"\"\"")) val = val.substring(3, val.length() - 3);
        else if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
        StringBuilder sb = new StringBuilder(val.length());
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            if (c == '\\' && i + 1 < val.length()) {
                char n = val.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '0' -> sb.append('\0');
                    case 's' -> sb.append(' ');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Decodes a char-literal token into the single char it represents,
     * supporting octal, {@code \\u}, and standard short escapes.
     *
     * @param raw raw char-literal token including quotes
     * @return decoded character
     */
    private char unquoteChar(@NotNull String raw) {
        String val = raw;
        if (val.startsWith("'") && val.endsWith("'")) val = val.substring(1, val.length() - 1);
        if (val.isEmpty()) return '\0';
        if (val.charAt(0) == '\\' && val.length() >= 2) {
            char n = val.charAt(1);
            if (n == 'u' && val.length() >= 6) return (char) Integer.parseInt(val.substring(2, 6), 16);
            if (n >= '0' && n <= '7') {
                int end = 2;
                while (end < val.length() && end < 4 && val.charAt(end) >= '0' && val.charAt(end) <= '7') end++;
                return (char) Integer.parseInt(val.substring(1, end), 8);
            }
            return switch (n) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 's' -> ' ';
                case '\\' -> '\\';
                case '\'' -> '\'';
                case '"' -> '"';
                default -> n;
            };
        }
        return val.charAt(0);
    }
}
