package net.vansencool.vanta.codegen.context;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.resolver.ExpressionTypeInferrer;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.scope.Scope;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds all mutable state needed during bytecode generation of a single method.
 * Includes the method visitor, scope, reachability tracking, and label context.
 */
public final class MethodContext {

    private final @NotNull MethodVisitor mv;
    private final @NotNull Scope scope;
    private final @NotNull LabelContext labelContext;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull MethodResolver methodResolver;
    private final @NotNull ExpressionTypeInferrer typeInferrer;
    private final @NotNull String classInternalName;
    private final @NotNull String superInternalName;
    private final boolean isStatic;
    private final @NotNull Map<String, SelfMethodInfo> selfMethods;
    private final @NotNull List<LocalVarEntry> localVarEntries = new ArrayList<>();
    private final @NotNull List<PendingLocal> pendingLocals = new ArrayList<>();
    private final @NotNull List<Runnable> pendingFinallyEmitters = new ArrayList<>();
    private final @NotNull Deque<ResolvedType> switchExpectedStack = new ArrayDeque<>();
    private final @NotNull Object anonClassLock = new Object();
    private int selfMethodsOverloadState;
    private boolean reachable;
    private @Nullable ClassWriter classWriter;
    private @Nullable AtomicInteger lambdaCounter;
    private @Nullable AtomicInteger anonClassCounter;
    private @Nullable String enclosingMethodName;
    private @Nullable ResolvedType returnType;
    private @NotNull List<byte[]> anonClassBytecodes;
    private @NotNull List<String> anonClassNames;
    private @Nullable Map<String, Map<String, ResolvedType>> nestedClassFields;
    private @Nullable Map<String, Map<String, SelfMethodInfo>> nestedClassMethods;
    private @Nullable Map<String, Map<String, Object>> nestedClassConstants;
    private @Nullable String enclosingOuterInternal;
    private @Nullable String enclosingStaticOuter;
    private @Nullable Label methodStartLabel;
    private int lastEmittedLine = -1;
    private @Nullable Map<String, ResolvedType> capturedFields;
    private @Nullable ClassGenerator classGenerator;

    /**
     * Creates a new method context.
     *
     * @param mv                the ASM method visitor
     * @param scope             the initial scope
     * @param typeResolver      the type resolver
     * @param methodResolver    the method resolver
     * @param classInternalName the internal name of the enclosing class
     * @param superInternalName the internal name of the superclass
     * @param isStatic          true if this is a static method
     * @param selfMethods       map of self-method keys to info for self-call resolution
     */
    public MethodContext(@NotNull MethodVisitor mv, @NotNull Scope scope, @NotNull TypeResolver typeResolver, @NotNull MethodResolver methodResolver, @NotNull String classInternalName, @NotNull String superInternalName, boolean isStatic, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        this.mv = mv;
        this.scope = scope;
        this.labelContext = new LabelContext();
        this.typeResolver = typeResolver;
        this.methodResolver = methodResolver;
        this.classInternalName = classInternalName;
        this.superInternalName = superInternalName;
        this.isStatic = isStatic;
        this.reachable = true;
        this.selfMethods = selfMethods;
        this.typeInferrer = new ExpressionTypeInferrer(scope, typeResolver, methodResolver.classpathManager(), classInternalName);
        this.typeInferrer.superInternalName(superInternalName);
        this.anonClassBytecodes = new ArrayList<>();
        this.anonClassNames = new ArrayList<>();
    }

    public @Nullable Map<String, Map<String, SelfMethodInfo>> nestedClassMethods() {
        return nestedClassMethods;
    }

    public void nestedClassMethods(@Nullable Map<String, Map<String, SelfMethodInfo>> nested) {
        this.nestedClassMethods = nested;
        this.typeInferrer.nestedClassMethods(nested);
    }

    public @Nullable Map<String, Map<String, Object>> nestedClassConstants() {
        return nestedClassConstants;
    }

    public void nestedClassConstants(@Nullable Map<String, Map<String, Object>> c) {
        this.nestedClassConstants = c;
    }

    /**
     * @return the outer-class internal name when this method belongs to a non-static inner
     * class that has a synthetic {@code this$0} field, or null otherwise
     */
    public @Nullable String enclosingOuterInternal() {
        return enclosingOuterInternal;
    }

    public void enclosingOuterInternal(@Nullable String outer) {
        this.enclosingOuterInternal = outer;
        this.typeInferrer.enclosingOuterInternal(outer);
    }

    /**
     * Returns the enclosing top-level class's internal name when the current
     * class is a static nested class, or null otherwise. Used by codegen to
     * route unqualified calls to outer static methods via {@code INVOKESTATIC}
     * on the enclosing class instead of falling through to a bogus
     * {@code INVOKEVIRTUAL} on self.
     */
    public @Nullable String enclosingStaticOuter() {
        return enclosingStaticOuter;
    }

    public void enclosingStaticOuter(@Nullable String outer) {
        this.enclosingStaticOuter = outer;
        this.typeInferrer.enclosingStaticOuter(outer);
    }

    /**
     * Registers a pending local variable to be emitted into the LocalVariableTable.
     */
    public void openLocal(@NotNull String name, @NotNull String descriptor, @Nullable String signature, @NotNull Label start, int index) {
        pendingLocals.add(new PendingLocal(name, descriptor, signature, start, index));
    }

    /**
     * Closes all pending locals whose index is {@code >= fromSlot} with {@code end} as
     * their end label, moving them into the final LocalVariableTable entries. Called at
     * scope-end points (block end, method end, loop end).
     */
    public void closeLocalsFrom(int fromSlot, @NotNull Label end) {
        Iterator<PendingLocal> it = pendingLocals.iterator();
        while (it.hasNext()) {
            PendingLocal p = it.next();
            if (p.index >= fromSlot) {
                localVarEntries.add(new LocalVarEntry(p.name, p.descriptor, p.signature, p.start, end, p.index));
                it.remove();
            }
        }
    }

    /**
     * @return all recorded LocalVariableTable entries
     */
    public @NotNull List<LocalVarEntry> localVarEntries() {
        return localVarEntries;
    }

    /**
     * Sets the method-start label so locals declared without an explicit start
     * position can use it as their range start. Called once per method.
     */
    public void setMethodStartLabel(@NotNull Label label) {
        this.methodStartLabel = label;
    }

    /**
     * Declares a local variable in the current scope and registers it for the
     * LocalVariableTable. Uses the method-start label as the range start to avoid
     * emitting one extra label per declaration; the actual lifetime starts no later
     * than the variable's first definite assignment so this is a safe over-approximation.
     */
    public @NotNull LocalVariable declareLocal(@NotNull String name, @NotNull ResolvedType type) {
        LocalVariable lv = scope.declare(name, type);
        Label start = methodStartLabel;
        if (start == null) {
            start = new Label();
            mv.visitLabel(start);
            methodStartLabel = start;
        }
        openLocal(name, type.descriptor(), null, start, lv.index());
        return lv;
    }

    /**
     * @return the ASM method visitor
     */
    public @NotNull MethodVisitor mv() {
        return mv;
    }

    /**
     * Emits a line-number entry bound to a freshly visited label at the current
     * code position, so the debugger sees this line pointing at real code. No-op
     * when the same line number was just emitted (matches javac's de-duplication).
     */
    public void emitLine(int line) {
        if (line == lastEmittedLine) return;
        if (!reachable) return;
        lastEmittedLine = line;
        Label label = new Label();
        mv.visitLabel(label);
        mv.visitLineNumber(line, label);
    }

    /**
     * @return the current scope
     */
    public @NotNull Scope scope() {
        return scope;
    }

    /**
     * @return the label context for break/continue
     */
    public @NotNull LabelContext labelContext() {
        return labelContext;
    }

    /**
     * @return the type resolver
     */
    public @NotNull TypeResolver typeResolver() {
        return typeResolver;
    }

    /**
     * @return the method resolver
     */
    public @NotNull MethodResolver methodResolver() {
        return methodResolver;
    }

    /**
     * @return the expression type inferrer
     */
    public @NotNull ExpressionTypeInferrer typeInferrer() {
        return typeInferrer;
    }

    /**
     * @return the internal name of the enclosing class
     */
    public @NotNull String classInternalName() {
        return classInternalName;
    }

    /**
     * @return the internal name of the superclass
     */
    public @NotNull String superInternalName() {
        return superInternalName;
    }

    /**
     * @return true if this is a static method
     */
    public boolean isStatic() {
        return isStatic;
    }

    /**
     * @return the self-methods map for self-call resolution
     */
    public @NotNull Map<String, SelfMethodInfo> selfMethods() {
        return selfMethods;
    }

    /**
     * @return true if the self-methods map contains any overload keys (those with {@code #desc} suffixes)
     */
    public boolean selfMethodsHasOverloads() {
        if (selfMethodsOverloadState != 0) return selfMethodsOverloadState > 0;
        boolean found = false;
        for (String k : selfMethods.keySet()) {
            if (k.indexOf('#') >= 0) {
                found = true;
                break;
            }
        }
        selfMethodsOverloadState = found ? 1 : -1;
        return found;
    }

    /**
     * @return true if code at the current point is reachable
     */
    public boolean isReachable() {
        return reachable;
    }

    /**
     * Marks the current code point as unreachable (after return, throw, or unconditional goto).
     */
    public void markUnreachable() {
        reachable = false;
    }

    /**
     * Marks the current code point as reachable (at a branch target label).
     */
    public void markReachable() {
        reachable = true;
    }

    public void setupLambdaSupport(@NotNull ClassWriter classWriter, @NotNull AtomicInteger lambdaCounter, @NotNull String enclosingMethodName) {
        this.classWriter = classWriter;
        this.lambdaCounter = lambdaCounter;
        this.enclosingMethodName = enclosingMethodName;
    }

    public void setupAnonClassSupport(@Nullable ClassGenerator classGenerator, @NotNull ClassWriter classWriter, @NotNull AtomicInteger anonClassCounter, @NotNull String enclosingMethodName, @NotNull List<byte[]> anonClassBytecodes, @NotNull List<String> anonClassNames) {
        this.classGenerator = classGenerator;
        this.classWriter = classWriter;
        this.anonClassCounter = anonClassCounter;
        this.enclosingMethodName = enclosingMethodName;
        this.anonClassBytecodes = anonClassBytecodes;
        this.anonClassNames = anonClassNames;
    }

    public void addAnonClass(byte[] bytecode, @NotNull String name) {
        synchronized (anonClassLock) {
            anonClassBytecodes.add(bytecode);
            anonClassNames.add(name);
        }
    }

    public int nextAnonClassIndex() {
        return anonClassCounter != null ? anonClassCounter.incrementAndGet() : 1;
    }

    public @Nullable ClassWriter classWriter() {
        return classWriter;
    }

    /**
     * @return the underlying class-scoped lambda counter so nested lambdas can share it
     */
    public @Nullable AtomicInteger anonClassCounter() {
        return anonClassCounter;
    }

    public @Nullable AtomicInteger lambdaCounter() {
        return lambdaCounter;
    }

    public @NotNull List<byte[]> anonClassBytecodes() {
        return anonClassBytecodes;
    }

    public @NotNull List<String> anonClassNames() {
        return anonClassNames;
    }

    public @Nullable String enclosingMethodName() {
        return enclosingMethodName;
    }

    public @Nullable ResolvedType returnType() {
        return returnType;
    }

    public void returnType(@Nullable ResolvedType returnType) {
        this.returnType = returnType;
    }

    public @Nullable Map<String, Map<String, ResolvedType>> nestedClassFields() {
        return nestedClassFields;
    }

    public void nestedClassFields(@NotNull Map<String, Map<String, ResolvedType>> nestedClassFields) {
        this.nestedClassFields = nestedClassFields;
        this.typeInferrer.nestedClassFields(nestedClassFields);
    }

    /**
     * Returns a map of captured-variable-name to its synthetic-field type for this method.
     * Non-null when compiling an anonymous class body with outer-local captures; anon-class
     * methods use this to route free-variable reads through {@code GETFIELD val$X} on the
     * enclosing anonymous instance instead of treating the name as an undefined local.
     */
    public @Nullable Map<String, ResolvedType> capturedFields() {
        return capturedFields;
    }

    public void capturedFields(@Nullable Map<String, ResolvedType> capturedFields) {
        this.capturedFields = capturedFields;
        this.typeInferrer.capturedFields(capturedFields);
    }

    /**
     * Pushes a finally-body emitter (or resource-close + finally-body) that
     * must run before any {@code return}/{@code throw}/{@code break}/
     * {@code continue} that would otherwise escape the enclosing try block.
     */
    public void pushFinally(@NotNull Runnable emitter) {
        pendingFinallyEmitters.add(emitter);
    }

    public void popFinally() {
        if (!pendingFinallyEmitters.isEmpty()) pendingFinallyEmitters.remove(pendingFinallyEmitters.size() - 1);
    }

    public void pushSwitchExpected(@Nullable ResolvedType expected) {
        switchExpectedStack.push(expected != null ? expected : ResolvedType.ofObject("java/lang/Object"));
    }

    public void popSwitchExpected() {
        if (!switchExpectedStack.isEmpty()) switchExpectedStack.pop();
    }

    public @Nullable ResolvedType currentSwitchExpected() {
        return switchExpectedStack.peek();
    }

    /**
     * Runs every currently pending finally emitter, most-recent first, used
     * by {@code return} to inline all enclosing finallies before the return
     * instruction.
     */
    public void runAllFinallies() {
        for (int i = pendingFinallyEmitters.size() - 1; i >= 0; i--) {
            pendingFinallyEmitters.get(i).run();
        }
    }

    public boolean runAllFinalliesIsEmpty() {
        return pendingFinallyEmitters.isEmpty();
    }

    public @Nullable ClassGenerator classGenerator() {
        return classGenerator;
    }

    public void classGenerator(@Nullable ClassGenerator classGenerator) {
        this.classGenerator = classGenerator;
    }

    /**
     * Records a local variable for the LocalVariableTable attribute. Called when a local
     * is declared; {@code start} is a label at the declaration site. The end label is
     * supplied later when the enclosing scope closes.
     */
    public record LocalVarEntry(@NotNull String name, @NotNull String descriptor, @Nullable String signature,
                                @NotNull Label start, @NotNull Label end, int index) {
    }

    /**
     * Tracks a local variable whose lifetime spans from a start label to an end label
     * emitted later. Resolved into a {@link LocalVarEntry} when the end label arrives.
     */
    private record PendingLocal(@NotNull String name, @NotNull String descriptor, @Nullable String signature,
                                @NotNull Label start, int index) {
    }
}
