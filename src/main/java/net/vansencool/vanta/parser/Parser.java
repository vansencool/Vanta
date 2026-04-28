package net.vansencool.vanta.parser;

import net.vansencool.vanta.lexer.token.Token;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.AnnotationNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.ImportDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.declaration.RecordComponent;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.SuperExpression;
import net.vansencool.vanta.parser.ast.expression.SwitchExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.expression.ThisExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.statement.AssertStatement;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.BreakStatement;
import net.vansencool.vanta.parser.ast.statement.CatchClause;
import net.vansencool.vanta.parser.ast.statement.ContinueStatement;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
import net.vansencool.vanta.parser.ast.statement.LabeledStatement;
import net.vansencool.vanta.parser.ast.statement.ResourceDeclaration;
import net.vansencool.vanta.parser.ast.statement.ReturnStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.SwitchCase;
import net.vansencool.vanta.parser.ast.statement.SwitchStatement;
import net.vansencool.vanta.parser.ast.statement.SynchronizedStatement;
import net.vansencool.vanta.parser.ast.statement.ThrowStatement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarationStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarator;
import net.vansencool.vanta.parser.ast.statement.WhileStatement;
import net.vansencool.vanta.parser.ast.statement.YieldStatement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import net.vansencool.vanta.parser.exception.ParserException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.vansencool.vanta.lexer.token.TokenType.*;

/**
 * Recursive descent parser that converts a token stream into an AST.
 * Handles the full Java 17 grammar (excluding lambdas, sealed classes, throws).
 */
public final class Parser {

    private @NotNull Token @NotNull [] tokens;
    private int tokenCount;
    private int pos;

    /**
     * Creates a parser for the given token list.
     *
     * @param tokens the token list
     */
    public Parser(@NotNull List<Token> tokens) {
        this.tokens = tokens.toArray(new Token[0]);
        this.tokenCount = this.tokens.length;
        this.pos = 0;
    }

    /**
     * Parses the token stream into a CompilationUnit.
     *
     * @return the parsed compilation unit
     */
    public @NotNull CompilationUnit parse() {
        int startLine = current().line();
        String packageName = null;
        parseAnnotations();
        if (check(PACKAGE)) {
            packageName = parsePackageDeclaration();
        }
        List<ImportDeclaration> imports = new ArrayList<>();
        while (check(IMPORT)) {
            imports.add(parseImportDeclaration());
        }
        List<AstNode> typeDecls = new ArrayList<>();
        while (!check(EOF)) {
            typeDecls.add(parseTypeDeclaration());
        }
        return new CompilationUnit(packageName, imports, typeDecls, startLine);
    }

    /**
     * Parses a package declaration.
     *
     * @return the fully qualified package name
     */
    private @NotNull String parsePackageDeclaration() {
        expect(PACKAGE);
        String name = parseQualifiedName();
        expect(SEMICOLON);
        return name;
    }

    /**
     * Parses an import declaration.
     *
     * @return the import declaration node
     */
    private @NotNull ImportDeclaration parseImportDeclaration() {
        int line = current().line();
        expect(IMPORT);
        boolean isStatic = false;
        if (check(STATIC)) {
            advance();
            isStatic = true;
        }
        String name = parseQualifiedName();
        boolean isWildcard = false;
        if (check(DOT) && peek().type() == STAR) {
            advance();
            advance();
            name = name + ".*";
            isWildcard = true;
        }
        expect(SEMICOLON);
        return new ImportDeclaration(name, isStatic, isWildcard, line);
    }

    /**
     * Parses a top level type declaration (class, interface, enum, or record).
     *
     * @return the type declaration node
     */
    private @NotNull AstNode parseTypeDeclaration() {
        List<AnnotationNode> annotations = parseAnnotations();
        int modifiers = parseModifiers();
        if (check(AT) && peek().type() == INTERFACE) {
            return parseAnnotationDeclaration(modifiers, annotations);
        }
        if (check(CLASS)) {
            return parseClassDeclaration(modifiers, annotations);
        }
        if (check(INTERFACE)) {
            return parseInterfaceDeclaration(modifiers, annotations);
        }
        if (check(ENUM)) {
            return parseEnumDeclaration(modifiers, annotations);
        }
        if (check(RECORD)) {
            return parseRecordDeclaration(modifiers, annotations);
        }
        throw error("Expected class, interface, enum, or record declaration");
    }

    /**
     * Parses an annotation type declaration ({@code @interface Name { ... }}). Each member
     * is an abstract method with optional {@code default} value, modeled as a regular
     * MethodDeclaration whose body is null.
     */
    private @NotNull ClassDeclaration parseAnnotationDeclaration(int modifiers, @NotNull List<AnnotationNode> annotations) {
        int line = current().line();
        expect(AT);
        expect(INTERFACE);
        String name = expectIdentifier();
        expect(LEFT_BRACE);
        List<AstNode> members = new ArrayList<>();
        while (!check(RIGHT_BRACE)) {
            members.add(parseAnnotationMember());
        }
        expect(RIGHT_BRACE);
        return new ClassDeclaration(name, modifiers, null, null, new ArrayList<>(), members, annotations, TypeKind.ANNOTATION, null, null, line);
    }

    private @NotNull AstNode parseAnnotationMember() {
        List<AnnotationNode> memberAnnotations = parseAnnotations();
        int memberModifiers = parseModifiers();
        if (check(CLASS) || check(INTERFACE) || check(ENUM) || (check(AT) && peek().type() == INTERFACE)) {
            if (check(AT)) return parseAnnotationDeclaration(memberModifiers, memberAnnotations);
            if (check(CLASS)) return parseClassDeclaration(memberModifiers, memberAnnotations);
            if (check(INTERFACE)) return parseInterfaceDeclaration(memberModifiers, memberAnnotations);
            return parseEnumDeclaration(memberModifiers, memberAnnotations);
        }
        int line = current().line();
        TypeNode returnType = parseType();
        String name = expectIdentifier();
        if (!check(LEFT_PAREN)) {
            return parseFieldDeclaration(memberModifiers, returnType, name, memberAnnotations, line);
        }
        expect(LEFT_PAREN);
        expect(RIGHT_PAREN);
        Expression defaultValue = null;
        if (current().type() == TokenType.DEFAULT) {
            advance();
            defaultValue = parseAnnotationValue();
        }
        expect(SEMICOLON);
        int access = memberModifiers | 0x0001 | 0x0400;
        return new MethodDeclaration(name, access, returnType, null, new ArrayList<>(), null, defaultValue, memberAnnotations, false, line);
    }

    private @NotNull FieldDeclaration parseFieldDeclaration(int modifiers, @NotNull TypeNode type, @NotNull String firstName, @NotNull List<AnnotationNode> annotations, int line) {
        List<FieldDeclarator> declarators = new ArrayList<>();
        Expression init = null;
        if (check(ASSIGN)) {
            advance();
            init = parseExpression();
        }
        declarators.add(new FieldDeclarator(firstName, 0, init));
        while (check(COMMA)) {
            advance();
            String n = expectIdentifier();
            Expression i = null;
            if (check(ASSIGN)) {
                advance();
                i = parseExpression();
            }
            declarators.add(new FieldDeclarator(n, 0, i));
        }
        expect(SEMICOLON);
        return new FieldDeclaration(type, declarators, modifiers, annotations, line);
    }

    /**
     * Parses a class declaration.
     *
     * @param modifiers   the already parsed modifiers
     * @param annotations the already parsed annotations
     * @return the class declaration node
     */
    private @NotNull ClassDeclaration parseClassDeclaration(int modifiers, @NotNull List<AnnotationNode> annotations) {
        int line = current().line();
        expect(CLASS);
        String name = expectIdentifier();
        List<TypeParameter> typeParams = null;
        if (check(LESS)) {
            typeParams = parseTypeParameters();
        }
        TypeNode superClass = null;
        if (check(EXTENDS)) {
            advance();
            superClass = parseType();
        }
        List<TypeNode> interfaces = new ArrayList<>();
        if (check(IMPLEMENTS)) {
            advance();
            interfaces = parseTypeList();
        }
        List<AstNode> members = parseClassBody(name);
        return new ClassDeclaration(name, modifiers, typeParams, superClass, interfaces, members, annotations, TypeKind.CLASS, null, null, line);
    }

    /**
     * Parses an interface declaration.
     *
     * @param modifiers   the already parsed modifiers
     * @param annotations the already parsed annotations
     * @return the class declaration node with INTERFACE kind
     */
    private @NotNull ClassDeclaration parseInterfaceDeclaration(int modifiers, @NotNull List<AnnotationNode> annotations) {
        int line = current().line();
        expect(INTERFACE);
        String name = expectIdentifier();
        List<TypeParameter> typeParams = null;
        if (check(LESS)) {
            typeParams = parseTypeParameters();
        }
        List<TypeNode> interfaces = new ArrayList<>();
        if (check(EXTENDS)) {
            advance();
            interfaces = parseTypeList();
        }
        List<AstNode> members = parseClassBody(name);
        return new ClassDeclaration(name, modifiers, typeParams, null, interfaces, members, annotations, TypeKind.INTERFACE, null, null, line);
    }

    /**
     * Parses an enum declaration.
     *
     * @param modifiers   the already parsed modifiers
     * @param annotations the already parsed annotations
     * @return the class declaration node with ENUM kind
     */
    private @NotNull ClassDeclaration parseEnumDeclaration(int modifiers, @NotNull List<AnnotationNode> annotations) {
        int line = current().line();
        expect(ENUM);
        String name = expectIdentifier();
        List<TypeNode> interfaces = new ArrayList<>();
        if (check(IMPLEMENTS)) {
            advance();
            interfaces = parseTypeList();
        }
        expect(LEFT_BRACE);
        List<EnumConstant> constants = parseEnumConstants();
        List<AstNode> members = new ArrayList<>();
        if (check(SEMICOLON)) {
            advance();
            while (!check(RIGHT_BRACE)) {
                members.add(parseMember(name));
            }
        }
        expect(RIGHT_BRACE);
        return new ClassDeclaration(name, modifiers, null, null, interfaces, members, annotations, TypeKind.ENUM, constants, null, line);
    }

    /**
     * Parses enum constants before the semicolon or closing brace.
     *
     * @return the list of enum constants
     */
    private @NotNull List<EnumConstant> parseEnumConstants() {
        List<EnumConstant> constants = new ArrayList<>();
        if (check(RIGHT_BRACE) || check(SEMICOLON)) {
            return constants;
        }
        constants.add(parseEnumConstant());
        while (check(COMMA)) {
            advance();
            if (check(RIGHT_BRACE) || check(SEMICOLON)) {
                break;
            }
            constants.add(parseEnumConstant());
        }
        return constants;
    }

    /**
     * Parses a single enum constant.
     *
     * @return the enum constant node
     */
    private @NotNull EnumConstant parseEnumConstant() {
        List<AnnotationNode> annotations = parseAnnotations();
        int line = current().line();
        String name = expectIdentifier();
        List<Expression> arguments = new ArrayList<>();
        if (check(LEFT_PAREN)) {
            arguments = parseArguments();
        }
        List<AstNode> classBody = null;
        if (check(LEFT_BRACE)) {
            classBody = parseAnonymousClassBody();
        }
        return new EnumConstant(name, arguments, classBody, annotations, line);
    }

    /**
     * Parses a record declaration.
     *
     * @param modifiers   the already parsed modifiers
     * @param annotations the already parsed annotations
     * @return the class declaration node with RECORD kind
     */
    private @NotNull ClassDeclaration parseRecordDeclaration(int modifiers, @NotNull List<AnnotationNode> annotations) {
        int line = current().line();
        advance();
        String name = expectIdentifier();
        List<TypeParameter> typeParams = null;
        if (check(LESS)) {
            typeParams = parseTypeParameters();
        }
        expect(LEFT_PAREN);
        List<RecordComponent> components = parseRecordComponents();
        expect(RIGHT_PAREN);
        List<TypeNode> interfaces = new ArrayList<>();
        if (check(IMPLEMENTS)) {
            advance();
            interfaces = parseTypeList();
        }
        List<AstNode> members = parseClassBody(name);
        return new ClassDeclaration(name, modifiers, typeParams, null, interfaces, members, annotations, TypeKind.RECORD, null, components, line);
    }

    /**
     * Parses record components within parentheses.
     *
     * @return the list of record components
     */
    private @NotNull List<RecordComponent> parseRecordComponents() {
        List<RecordComponent> components = new ArrayList<>();
        if (check(RIGHT_PAREN)) {
            return components;
        }
        components.add(parseRecordComponent());
        while (check(COMMA)) {
            advance();
            components.add(parseRecordComponent());
        }
        return components;
    }

    /**
     * Parses a single record component.
     *
     * @return the record component
     */
    private @NotNull RecordComponent parseRecordComponent() {
        List<AnnotationNode> annotations = parseAnnotations();
        TypeNode type = parseType();
        String name = expectIdentifier();
        return new RecordComponent(type, name, annotations);
    }

    /**
     * Parses a class body (between curly braces).
     *
     * @param className the enclosing class name (for method detection)
     * @return the list of member declarations
     */
    private @NotNull List<AstNode> parseClassBody(@NotNull String className) {
        expect(LEFT_BRACE);
        List<AstNode> members = new ArrayList<>();
        while (!check(RIGHT_BRACE)) {
            members.add(parseMember(className));
        }
        expect(RIGHT_BRACE);
        return members;
    }

    /**
     * Parses a type member (method, field, or nested type declaration).
     *
     * @param enclosingName the enclosing type name
     * @return the member node
     */
    private @NotNull AstNode parseMember(@NotNull String enclosingName) {
        if (check(SEMICOLON)) {
            advance();
            return parseMember(enclosingName);
        }

        List<AnnotationNode> annotations = parseAnnotations();
        int modifiers = parseModifiers();

        if (check(LEFT_BRACE) && (modifiers & 0x0008) != 0) {
            BlockStatement body = parseBlock();
            return new MethodDeclaration("<clinit>", 0x0008, new TypeNode("void", null, 0, body.line()), null, List.of(), body, null, annotations, false, body.line());
        }
        if (check(LEFT_BRACE) && modifiers == 0 && annotations.isEmpty()) {
            BlockStatement body = parseBlock();
            return new MethodDeclaration("<iinit>", 0, new TypeNode("void", null, 0, body.line()), null, List.of(), body, null, annotations, false, body.line());
        }

        if (check(CLASS)) {
            return parseClassDeclaration(modifiers, annotations);
        }
        if (check(INTERFACE)) {
            return parseInterfaceDeclaration(modifiers, annotations);
        }
        if (check(ENUM)) {
            return parseEnumDeclaration(modifiers, annotations);
        }
        if (check(RECORD)) {
            return parseRecordDeclaration(modifiers, annotations);
        }

        if (check(DEFAULT)) {
            advance();
        }

        List<TypeParameter> methodTypeParams = null;
        if (check(LESS)) {
            int saved = pos;
            try {
                methodTypeParams = parseTypeParameters();
            } catch (ParserException e) {
                pos = saved;
            }
        }

        TypeNode returnType = parseType();

        if (returnType.name().equals(enclosingName) && check(LEFT_PAREN)) {
            return parseMethodDeclaration("<init>", modifiers, new TypeNode("void", null, 0, returnType.line()), annotations, methodTypeParams);
        }

        if (returnType.name().equals(enclosingName) && check(LEFT_BRACE)) {
            BlockStatement body = parseBlock();
            return new MethodDeclaration("<init>", modifiers, new TypeNode("void", null, 0, returnType.line()), methodTypeParams, List.of(), body, null, annotations, false, returnType.line());
        }

        String name = expectIdentifier();

        if (check(LEFT_PAREN)) {
            return parseMethodDeclaration(name, modifiers, returnType, annotations, methodTypeParams);
        }

        return parseFieldDeclaration(name, modifiers, returnType, annotations);
    }

    /**
     * Parses a method declaration.
     *
     * @param name           the method name
     * @param modifiers      the modifiers
     * @param returnType     the return type
     * @param annotations    the annotations
     * @param typeParameters the type parameters
     * @return the method declaration node
     */
    private @NotNull MethodDeclaration parseMethodDeclaration(@NotNull String name, int modifiers, @NotNull TypeNode returnType, @NotNull List<AnnotationNode> annotations, @Nullable List<TypeParameter> typeParameters) {
        int line = current().line();
        expect(LEFT_PAREN);
        List<Parameter> params = new ArrayList<>();
        boolean isVarargs = false;
        if (!check(RIGHT_PAREN)) {
            Parameter p = parseParameter();
            params.add(p);
            isVarargs = p.isVarargs();
            while (check(COMMA) && !isVarargs) {
                advance();
                p = parseParameter();
                params.add(p);
                isVarargs = p.isVarargs();
            }
        }
        expect(RIGHT_PAREN);

        int extraDims = 0;
        while (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
            advance();
            advance();
            extraDims++;
        }
        if (extraDims > 0) {
            returnType = returnType.withExtraDimensions(extraDims);
        }

        if (check(THROWS)) {
            do {
                advance();
                parseType();
            } while (check(COMMA));
        }

        Expression defaultValue = null;
        if (check(DEFAULT)) {
            advance();
            defaultValue = parseExpression();
        }

        BlockStatement body = null;
        if (check(LEFT_BRACE)) {
            body = parseBlock();
        } else {
            expect(SEMICOLON);
        }

        return new MethodDeclaration(name, modifiers, returnType, typeParameters, params, body, defaultValue, annotations, isVarargs, line);
    }

    /**
     * Parses a field declaration.
     *
     * @param firstName   the first field name (already consumed)
     * @param modifiers   the modifiers
     * @param type        the field type
     * @param annotations the annotations
     * @return the field declaration node
     */
    private @NotNull FieldDeclaration parseFieldDeclaration(@NotNull String firstName, int modifiers, @NotNull TypeNode type, @NotNull List<AnnotationNode> annotations) {
        int line = current().line();
        List<FieldDeclarator> declarators = new ArrayList<>();

        int extraDims = 0;
        while (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
            advance();
            advance();
            extraDims++;
        }

        Expression init = null;
        if (check(ASSIGN)) {
            advance();
            init = parseInitializer();
        }
        declarators.add(new FieldDeclarator(firstName, extraDims, init));

        while (check(COMMA)) {
            advance();
            String name = expectIdentifier();
            int dims = 0;
            while (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
                advance();
                advance();
                dims++;
            }
            Expression init2 = null;
            if (check(ASSIGN)) {
                advance();
                init2 = parseInitializer();
            }
            declarators.add(new FieldDeclarator(name, dims, init2));
        }
        expect(SEMICOLON);
        return new FieldDeclaration(type, declarators, modifiers, annotations, line);
    }

    /**
     * Parses an initializer expression (can be an array initializer or regular expression).
     *
     * @return the initializer expression
     */
    private @NotNull Expression parseInitializer() {
        if (check(LEFT_BRACE)) {
            return parseArrayInitializer();
        }
        return parseExpression();
    }

    /**
     * Parses an array initializer { expr, expr, ... }.
     *
     * @return the array initializer expression
     */
    private @NotNull ArrayInitializerExpression parseArrayInitializer() {
        int line = current().line();
        expect(LEFT_BRACE);
        List<Expression> elements = new ArrayList<>();
        if (!check(RIGHT_BRACE)) {
            elements.add(parseInitializer());
            while (check(COMMA)) {
                advance();
                if (check(RIGHT_BRACE)) {
                    break;
                }
                elements.add(parseInitializer());
            }
        }
        expect(RIGHT_BRACE);
        return new ArrayInitializerExpression(elements, line);
    }

    /**
     * Parses a parameter declaration.
     *
     * @return the parameter node
     */
    private @NotNull Parameter parseParameter() {
        List<AnnotationNode> annotations = parseAnnotations();
        int modifiers = parseModifiers();
        TypeNode type = parseType();
        boolean isVarargs = false;
        if (check(ELLIPSIS)) {
            advance();
            isVarargs = true;
        }
        String name = expectIdentifier();
        int extraDims = 0;
        while (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
            advance();
            advance();
            extraDims++;
        }
        if (extraDims > 0) {
            type = type.withExtraDimensions(extraDims);
        }
        if (isVarargs) {
            type = type.withExtraDimensions(1);
        }
        return new Parameter(type, name, modifiers, annotations, isVarargs);
    }

    /**
     * Parses the content of an anonymous class body.
     *
     * @return the list of members
     */
    private @NotNull List<AstNode> parseAnonymousClassBody() {
        expect(LEFT_BRACE);
        List<AstNode> members = new ArrayList<>();
        while (!check(RIGHT_BRACE)) {
            members.add(parseMember(""));
        }
        expect(RIGHT_BRACE);
        return members;
    }

    /**
     * Parses a comma separated list of types.
     *
     * @return the list of type nodes
     */
    private @NotNull List<TypeNode> parseTypeList() {
        List<TypeNode> types = new ArrayList<>();
        types.add(parseType());
        while (check(COMMA)) {
            advance();
            types.add(parseType());
        }
        return types;
    }

    /**
     * Parses type parameters (e.g., &lt;T, U extends Comparable&lt;U&gt;&gt;).
     *
     * @return the list of type parameters
     */
    private @NotNull List<TypeParameter> parseTypeParameters() {
        expect(LESS);
        List<TypeParameter> params = new ArrayList<>();
        params.add(parseTypeParameter());
        while (check(COMMA)) {
            advance();
            params.add(parseTypeParameter());
        }
        expect(GREATER);
        return params;
    }

    /**
     * Parses a single type parameter.
     *
     * @return the type parameter node
     */
    private @NotNull TypeParameter parseTypeParameter() {
        int line = current().line();
        String name = expectIdentifier();
        List<TypeNode> bounds = null;
        if (check(EXTENDS)) {
            advance();
            bounds = new ArrayList<>();
            bounds.add(parseType());
            while (check(AMPERSAND)) {
                advance();
                bounds.add(parseType());
            }
        }
        return new TypeParameter(name, bounds, line);
    }

    /**
     * Parses a type reference.
     *
     * @return the type node
     */
    private @NotNull TypeNode parseType() {
        int line = current().line();
        parseAnnotations();
        String name = parseTypeName();
        List<TypeNode> typeArgs = null;
        if (check(LESS)) {
            typeArgs = parseTypeArguments();
        }
        int dims = 0;
        while (true) {
            parseAnnotations();
            if (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
                advance();
                advance();
                dims++;
            } else {
                break;
            }
        }
        return new TypeNode(name, typeArgs, dims, line);
    }

    /**
     * Parses a type name (simple or qualified).
     *
     * @return the type name string
     */
    private @NotNull String parseTypeName() {
        TokenType t = current().type();
        if (t.isPrimitive() || t == VOID) {
            String name = current().value();
            advance();
            return name;
        }
        if (t == VAR) {
            advance();
            return "var";
        }
        return parseQualifiedName();
    }

    /**
     * Parses generic type arguments (e.g., &lt;String, Integer&gt;).
     *
     * @return the list of type argument nodes
     */
    private @NotNull List<TypeNode> parseTypeArguments() {
        expect(LESS);
        List<TypeNode> args = new ArrayList<>();
        if (check(GREATER)) {
            advance();
            return args;
        }
        args.add(parseTypeArgument());
        while (check(COMMA)) {
            advance();
            args.add(parseTypeArgument());
        }
        expectTypeArgumentClose();
        return args;
    }

    /**
     * Parses a single type argument (may be a wildcard).
     *
     * @return the type argument node
     */
    private @NotNull TypeNode parseTypeArgument() {
        if (check(QUESTION)) {
            int line = current().line();
            advance();
            if (check(EXTENDS)) {
                advance();
                TypeNode bound = parseType();
                return new TypeNode("? extends " + bound, null, 0, line);
            }
            if (check(SUPER)) {
                advance();
                TypeNode bound = parseType();
                return new TypeNode("? super " + bound, null, 0, line);
            }
            return new TypeNode("?", null, 0, line);
        }
        return parseType();
    }

    /**
     * Parses annotations before declarations.
     *
     * @return the list of annotation nodes
     */
    private @NotNull List<AnnotationNode> parseAnnotations() {
        List<AnnotationNode> annotations = new ArrayList<>();
        while (check(AT) && peek().type() != INTERFACE) {
            annotations.add(parseAnnotation());
        }
        return annotations;
    }

    /**
     * Parses a single annotation.
     *
     * @return the annotation node
     */
    private @NotNull AnnotationNode parseAnnotation() {
        int line = current().line();
        expect(AT);
        String name = parseQualifiedName();
        Map<String, Expression> attrs = null;
        if (check(LEFT_PAREN)) {
            advance();
            if (!check(RIGHT_PAREN)) {
                attrs = new LinkedHashMap<>();
                if (check(IDENTIFIER) && peek().type() == ASSIGN) {
                    do {
                        if (check(COMMA)) advance();
                        String attrName = expectIdentifier();
                        expect(ASSIGN);
                        Expression value = parseAnnotationValue();
                        attrs.put(attrName, value);
                    } while (check(COMMA));
                } else {
                    Expression value = parseAnnotationValue();
                    attrs.put("value", value);
                }
            }
            expect(RIGHT_PAREN);
        }
        return new AnnotationNode(name, attrs, line);
    }

    /**
     * Parses an annotation value (expression, array initializer, or nested annotation).
     *
     * @return the expression representing the annotation value
     */
    private @NotNull Expression parseAnnotationValue() {
        if (check(LEFT_BRACE)) {
            return parseArrayInitializer();
        }
        return parseExpression();
    }

    /**
     * Parses access and other modifiers, returning them as a bitmask.
     *
     * @return the modifier bitmask using ASM Opcodes constants
     */
    private int parseModifiers() {
        int mods = 0;
        while (true) {
            switch (current().type()) {
                case PUBLIC -> {
                    mods |= 0x0001;
                    advance();
                }
                case PRIVATE -> {
                    mods |= 0x0002;
                    advance();
                }
                case PROTECTED -> {
                    mods |= 0x0004;
                    advance();
                }
                case STATIC -> {
                    mods |= 0x0008;
                    advance();
                }
                case FINAL -> {
                    mods |= 0x0010;
                    advance();
                }
                case ABSTRACT -> {
                    mods |= 0x0400;
                    advance();
                }
                case NATIVE -> {
                    mods |= 0x0100;
                    advance();
                }
                case SYNCHRONIZED -> {
                    mods |= 0x0020;
                    advance();
                }
                case TRANSIENT -> {
                    mods |= 0x0080;
                    advance();
                }
                case VOLATILE -> {
                    mods |= 0x0040;
                    advance();
                }
                case STRICTFP -> {
                    mods |= 0x0800;
                    advance();
                }
                default -> {
                    return mods;
                }
            }
        }
    }

    /**
     * Parses a block of statements.
     *
     * @return the block statement
     */
    private @NotNull BlockStatement parseBlock() {
        int line = current().line();
        expect(LEFT_BRACE);
        List<Statement> stmts = new ArrayList<>();
        while (!check(RIGHT_BRACE)) {
            stmts.add(parseStatement());
        }
        expect(RIGHT_BRACE);
        return new BlockStatement(stmts, line);
    }

    /**
     * Parses a single statement.
     *
     * @return the statement node
     */
    private @NotNull Statement parseStatement() {
        switch (current().type()) {
            case LEFT_BRACE:
                return parseBlock();
            case IF:
                return parseIfStatement();
            case WHILE:
                return parseWhileStatement();
            case DO:
                return parseDoWhileStatement();
            case FOR:
                return parseForStatement();
            case SWITCH:
                return parseSwitchStatement();
            case TRY:
                return parseTryStatement();
            case RETURN:
                return parseReturnStatement();
            case THROW:
                return parseThrowStatement();
            case BREAK:
                return parseBreakStatement();
            case CONTINUE:
                return parseContinueStatement();
            case YIELD:
                return parseYieldStatement();
            case SYNCHRONIZED:
                return parseSynchronizedStatement();
            case ASSERT:
                return parseAssertStatement();
            case SEMICOLON: {
                int line = current().line();
                advance();
                return new BlockStatement(Collections.emptyList(), line);
            }
            default:
                break;
        }

        if (check(IDENTIFIER) && peek().type() == COLON) {
            return parseLabeledStatement();
        }

        if (isLocalVariableDeclaration()) {
            return parseLocalVariableDeclaration();
        }

        return parseExpressionStatement();
    }

    /**
     * Parses an if statement.
     *
     * @return the if statement node
     */
    private @NotNull IfStatement parseIfStatement() {
        int line = current().line();
        expect(IF);
        expect(LEFT_PAREN);
        Expression condition = parseExpression();
        expect(RIGHT_PAREN);
        Statement thenBranch = parseStatement();
        Statement elseBranch = null;
        if (check(ELSE)) {
            advance();
            elseBranch = parseStatement();
        }
        return new IfStatement(condition, thenBranch, elseBranch, line);
    }

    /**
     * Parses a while statement.
     *
     * @return the while statement node
     */
    private @NotNull WhileStatement parseWhileStatement() {
        int line = current().line();
        expect(WHILE);
        expect(LEFT_PAREN);
        Expression condition = parseExpression();
        expect(RIGHT_PAREN);
        Statement body = parseStatement();
        return new WhileStatement(condition, body, line);
    }

    /**
     * Parses a do while statement.
     *
     * @return the do while statement node
     */
    private @NotNull DoWhileStatement parseDoWhileStatement() {
        int line = current().line();
        expect(DO);
        Statement body = parseStatement();
        expect(WHILE);
        expect(LEFT_PAREN);
        Expression condition = parseExpression();
        expect(RIGHT_PAREN);
        expect(SEMICOLON);
        return new DoWhileStatement(body, condition, line);
    }

    /**
     * Parses a for or enhanced for statement.
     *
     * @return the for or for each statement node
     */
    private @NotNull Statement parseForStatement() {
        int line = current().line();
        expect(FOR);
        expect(LEFT_PAREN);

        if (isForEach()) {
            return parseForEachRest(line);
        }

        List<Statement> inits = null;
        if (!check(SEMICOLON)) {
            inits = new ArrayList<>();
            if (isLocalVariableDeclaration()) {
                inits.add(parseLocalVariableDeclarationNoSemicolon());
            } else {
                inits.add(new ExpressionStatement(parseExpression(), current().line()));
                while (check(COMMA)) {
                    advance();
                    inits.add(new ExpressionStatement(parseExpression(), current().line()));
                }
            }
        }
        expect(SEMICOLON);

        Expression condition = null;
        if (!check(SEMICOLON)) {
            condition = parseExpression();
        }
        expect(SEMICOLON);

        List<Expression> updaters = null;
        if (!check(RIGHT_PAREN)) {
            updaters = new ArrayList<>();
            updaters.add(parseExpression());
            while (check(COMMA)) {
                advance();
                updaters.add(parseExpression());
            }
        }
        expect(RIGHT_PAREN);
        Statement body = parseStatement();
        return new ForStatement(inits, condition, updaters, body, line);
    }

    /**
     * Checks if the current for loop is an enhanced for (for each).
     *
     * @return true if this is an enhanced for loop
     */
    private boolean isForEach() {
        int saved = pos;
        try {
            parseAnnotations();
            parseModifiers();
            parseType();
            return check(IDENTIFIER) && peek().type() == COLON;
        } catch (ParserException e) {
            return false;
        } finally {
            pos = saved;
        }
    }

    /**
     * Parses the rest of an enhanced for loop after the opening paren.
     *
     * @param line the line of the for keyword
     * @return the for each statement
     */
    private @NotNull ForEachStatement parseForEachRest(int line) {
        parseAnnotations();
        int modifiers = parseModifiers();
        TypeNode type = parseType();
        String name = expectIdentifier();
        expect(COLON);
        Expression iterable = parseExpression();
        expect(RIGHT_PAREN);
        Statement body = parseStatement();
        return new ForEachStatement(type, name, iterable, body, modifiers, line);
    }

    /**
     * Parses a switch statement.
     *
     * @return the switch statement node
     */
    private @NotNull Statement parseSwitchStatement() {
        int line = current().line();
        expect(SWITCH);
        expect(LEFT_PAREN);
        Expression selector = parseExpression();
        expect(RIGHT_PAREN);
        expect(LEFT_BRACE);
        List<SwitchCase> cases = parseSwitchCases();
        expect(RIGHT_BRACE);
        return new SwitchStatement(selector, cases, line);
    }

    /**
     * Parses switch cases.
     *
     * @return the list of switch cases
     */
    private @NotNull List<SwitchCase> parseSwitchCases() {
        List<SwitchCase> cases = new ArrayList<>();
        while (!check(RIGHT_BRACE)) {
            int caseLine = current().line();
            boolean isDefault = false;
            List<Expression> labels = null;

            if (check(CASE)) {
                advance();
                labels = new ArrayList<>();
                labels.add(parseCaseLabel());
                while (check(COMMA)) {
                    advance();
                    labels.add(parseCaseLabel());
                }
            } else if (check(DEFAULT)) {
                advance();
                isDefault = true;
            } else {
                throw error("Expected 'case' or 'default'");
            }

            boolean isArrow = false;
            if (check(ARROW)) {
                advance();
                isArrow = true;
            } else {
                expect(COLON);
            }

            List<Statement> stmts = new ArrayList<>();
            if (isArrow) {
                if (check(LEFT_BRACE)) {
                    stmts.add(parseBlock());
                } else if (check(THROW)) {
                    stmts.add(parseThrowStatement());
                } else {
                    Expression expr = parseExpression();
                    expect(SEMICOLON);
                    stmts.add(new ExpressionStatement(expr, expr.line()));
                }
            } else {
                while (!check(CASE) && !check(DEFAULT) && !check(RIGHT_BRACE)) {
                    stmts.add(parseStatement());
                }
            }

            cases.add(new SwitchCase(labels, stmts, isDefault, isArrow, caseLine));
        }
        return cases;
    }

    private @NotNull Expression parseCaseLabel() {
        int line = current().line();
        TokenType type = current().type();
        if (type == IDENTIFIER || type == VAR || type == YIELD || type == RECORD) {
            String name = current().value();
            advance();
            if (check(DOT)) {
                advance();
                Expression target = new NameExpression(name, line);
                String fieldName = expectIdentifier();
                return new FieldAccessExpression(target, fieldName, line);
            }
            return new NameExpression(name, line);
        }
        if (type != NULL && type != TRUE && type != FALSE && !type.isPrimitive() && type != INT_LITERAL && type != LONG_LITERAL && type != FLOAT_LITERAL && type != DOUBLE_LITERAL && type != CHAR_LITERAL && type != STRING_LITERAL && type != TEXT_BLOCK && type != LEFT_PAREN && type != NEW && type != THIS && type != SUPER && type != SWITCH && type != MINUS) {
            String name = current().value();
            advance();
            return new NameExpression(name, line);
        }
        return parseExpression();
    }

    /**
     * Parses a try statement.
     *
     * @return the try statement node
     */
    private @NotNull TryStatement parseTryStatement() {
        int line = current().line();
        expect(TRY);
        List<ResourceDeclaration> resources = new ArrayList<>();
        if (check(LEFT_PAREN)) {
            advance();
            while (!check(RIGHT_PAREN)) {
                if (!resources.isEmpty()) {
                    expect(SEMICOLON);
                    if (check(RIGHT_PAREN)) break;
                }
                int resLine = current().line();
                TypeNode resType = parseType();
                String resName = expectIdentifier();
                expect(ASSIGN);
                Expression resInit = parseExpression();
                resources.add(new ResourceDeclaration(resType, resName, resInit, resLine));
            }
            expect(RIGHT_PAREN);
        }
        BlockStatement tryBlock = parseBlock();
        List<CatchClause> catches = new ArrayList<>();
        while (check(CATCH)) {
            catches.add(parseCatchClause());
        }
        BlockStatement finallyBlock = null;
        if (check(FINALLY)) {
            advance();
            finallyBlock = parseBlock();
        }
        return new TryStatement(resources, tryBlock, catches, finallyBlock, line);
    }

    /**
     * Parses a catch clause.
     *
     * @return the catch clause
     */
    private @NotNull CatchClause parseCatchClause() {
        int line = current().line();
        expect(CATCH);
        expect(LEFT_PAREN);
        List<TypeNode> types = new ArrayList<>();
        types.add(parseType());
        while (check(PIPE)) {
            advance();
            types.add(parseType());
        }
        String name = expectIdentifier();
        expect(RIGHT_PAREN);
        BlockStatement body = parseBlock();
        return new CatchClause(types, name, body, line);
    }

    /**
     * Parses a return statement.
     *
     * @return the return statement node
     */
    private @NotNull ReturnStatement parseReturnStatement() {
        int line = current().line();
        expect(RETURN);
        Expression value = null;
        if (!check(SEMICOLON)) {
            value = parseExpression();
        }
        expect(SEMICOLON);
        return new ReturnStatement(value, line);
    }

    /**
     * Parses a throw statement.
     *
     * @return the throw statement node
     */
    private @NotNull ThrowStatement parseThrowStatement() {
        int line = current().line();
        expect(THROW);
        Expression expr = parseExpression();
        expect(SEMICOLON);
        return new ThrowStatement(expr, line);
    }

    /**
     * Parses a break statement.
     *
     * @return the break statement node
     */
    private @NotNull BreakStatement parseBreakStatement() {
        int line = current().line();
        expect(BREAK);
        String label = null;
        if (check(IDENTIFIER)) {
            label = current().value();
            advance();
        }
        expect(SEMICOLON);
        return new BreakStatement(label, line);
    }

    /**
     * Parses a continue statement.
     *
     * @return the continue statement node
     */
    private @NotNull ContinueStatement parseContinueStatement() {
        int line = current().line();
        expect(CONTINUE);
        String label = null;
        if (check(IDENTIFIER)) {
            label = current().value();
            advance();
        }
        expect(SEMICOLON);
        return new ContinueStatement(label, line);
    }

    /**
     * Parses a yield statement.
     *
     * @return the yield statement node
     */
    private @NotNull YieldStatement parseYieldStatement() {
        int line = current().line();
        expect(YIELD);
        Expression value = parseExpression();
        expect(SEMICOLON);
        return new YieldStatement(value, line);
    }

    /**
     * Parses a synchronized statement.
     *
     * @return the synchronized statement node
     */
    private @NotNull SynchronizedStatement parseSynchronizedStatement() {
        int line = current().line();
        expect(SYNCHRONIZED);
        expect(LEFT_PAREN);
        Expression lock = parseExpression();
        expect(RIGHT_PAREN);
        BlockStatement body = parseBlock();
        return new SynchronizedStatement(lock, body, line);
    }

    /**
     * Parses an assert statement.
     *
     * @return the assert statement node
     */
    private @NotNull AssertStatement parseAssertStatement() {
        int line = current().line();
        expect(ASSERT);
        Expression condition = parseExpression();
        Expression message = null;
        if (check(COLON)) {
            advance();
            message = parseExpression();
        }
        expect(SEMICOLON);
        return new AssertStatement(condition, message, line);
    }

    /**
     * Parses a labeled statement.
     *
     * @return the labeled statement node
     */
    private @NotNull LabeledStatement parseLabeledStatement() {
        int line = current().line();
        String label = expectIdentifier();
        expect(COLON);
        Statement stmt = parseStatement();
        return new LabeledStatement(label, stmt, line);
    }

    /**
     * Parses an expression statement (expression followed by semicolon).
     *
     * @return the expression statement node
     */
    private @NotNull ExpressionStatement parseExpressionStatement() {
        int line = current().line();
        Expression expr = parseExpression();
        expect(SEMICOLON);
        return new ExpressionStatement(expr, line);
    }

    /**
     * Checks if the current position starts a local variable declaration.
     *
     * @return true if this looks like a local variable declaration
     */
    private boolean isLocalVariableDeclaration() {
        int saved = pos;
        try {
            parseAnnotations();
            parseModifiers();
            TokenType t = current().type();
            if (t.isPrimitive() || t == VAR) {
                return true;
            }
            if (t != IDENTIFIER) return false;
            parseType();
            return check(IDENTIFIER) || check(VAR) || check(YIELD) || check(RECORD);
        } catch (ParserException e) {
            return false;
        } finally {
            pos = saved;
        }
    }

    /**
     * Parses a local variable declaration statement.
     *
     * @return the variable declaration statement
     */
    private @NotNull VariableDeclarationStatement parseLocalVariableDeclaration() {
        VariableDeclarationStatement stmt = parseLocalVariableDeclarationNoSemicolon();
        expect(SEMICOLON);
        return stmt;
    }

    /**
     * Parses a local variable declaration without consuming the trailing semicolon.
     *
     * @return the variable declaration statement
     */
    private @NotNull VariableDeclarationStatement parseLocalVariableDeclarationNoSemicolon() {
        int line = current().line();
        List<AnnotationNode> annotations = parseAnnotations();
        int modifiers = parseModifiers();
        TypeNode type = parseType();
        List<VariableDeclarator> declarators = new ArrayList<>();
        declarators.add(parseVariableDeclarator());
        while (check(COMMA)) {
            advance();
            declarators.add(parseVariableDeclarator());
        }
        return new VariableDeclarationStatement(type, declarators, modifiers, annotations, line);
    }

    /**
     * Parses a single variable declarator.
     *
     * @return the variable declarator
     */
    private @NotNull VariableDeclarator parseVariableDeclarator() {
        String name = expectIdentifier();
        int dims = 0;
        while (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
            advance();
            advance();
            dims++;
        }
        Expression init = null;
        if (check(ASSIGN)) {
            advance();
            init = parseInitializer();
        }
        return new VariableDeclarator(name, dims, init);
    }

    /**
     * Parses an expression using precedence climbing.
     *
     * @return the expression node
     */
    public @NotNull Expression parseExpression() {
        return parseAssignment();
    }

    /**
     * Parses assignment expressions (right associative).
     *
     * @return the expression
     */
    private @NotNull Expression parseAssignment() {
        Expression left = parseTernary();
        if (current().type().isAssignment()) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression right = parseAssignment();
            return new AssignmentExpression(left, op, right, line);
        }
        return left;
    }

    /**
     * Parses ternary expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseTernary() {
        Expression condition = parseOr();
        if (check(QUESTION)) {
            int line = current().line();
            advance();
            Expression thenExpr = parseExpression();
            expect(COLON);
            Expression elseExpr = parseTernary();
            return new TernaryExpression(condition, thenExpr, elseExpr, line);
        }
        return condition;
    }

    /**
     * Parses logical OR expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseOr() {
        Expression left = parseAnd();
        while (check(OR)) {
            int line = current().line();
            advance();
            Expression right = parseAnd();
            left = new BinaryExpression(left, "||", right, line);
        }
        return left;
    }

    /**
     * Parses logical AND expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseAnd() {
        Expression left = parseBitwiseOr();
        while (check(AND)) {
            int line = current().line();
            advance();
            Expression right = parseBitwiseOr();
            left = new BinaryExpression(left, "&&", right, line);
        }
        return left;
    }

    /**
     * Parses bitwise OR expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseBitwiseOr() {
        Expression left = parseBitwiseXor();
        while (check(PIPE)) {
            int line = current().line();
            advance();
            Expression right = parseBitwiseXor();
            left = new BinaryExpression(left, "|", right, line);
        }
        return left;
    }

    /**
     * Parses bitwise XOR expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseBitwiseXor() {
        Expression left = parseBitwiseAnd();
        while (check(CARET)) {
            int line = current().line();
            advance();
            Expression right = parseBitwiseAnd();
            left = new BinaryExpression(left, "^", right, line);
        }
        return left;
    }

    /**
     * Parses bitwise AND expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseBitwiseAnd() {
        Expression left = parseEquality();
        while (check(AMPERSAND)) {
            int line = current().line();
            advance();
            Expression right = parseEquality();
            left = new BinaryExpression(left, "&", right, line);
        }
        return left;
    }

    /**
     * Parses equality expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseEquality() {
        Expression left = parseRelational();
        while (check(EQUAL) || check(NOT_EQUAL)) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression right = parseRelational();
            left = new BinaryExpression(left, op, right, line);
        }
        return left;
    }

    /**
     * Parses relational and instanceof expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseRelational() {
        Expression left = parseShift();
        while (true) {
            if (check(LESS) || check(GREATER) || check(LESS_EQUAL) || check(GREATER_EQUAL)) {
                int line = current().line();
                String op = current().value();
                advance();
                Expression right = parseShift();
                left = new BinaryExpression(left, op, right, line);
            } else if (check(INSTANCEOF)) {
                int line = current().line();
                advance();
                TypeNode type = parseType();
                String patternVar = null;
                if (check(IDENTIFIER) || check(VAR) || check(YIELD) || check(RECORD)) {
                    patternVar = current().value();
                    advance();
                }
                left = new InstanceofExpression(left, type, patternVar, line);
            } else {
                break;
            }
        }
        return left;
    }

    /**
     * Parses shift expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseShift() {
        Expression left = parseAdditive();
        while (check(LEFT_SHIFT) || check(RIGHT_SHIFT) || check(UNSIGNED_RIGHT_SHIFT)) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression right = parseAdditive();
            left = new BinaryExpression(left, op, right, line);
        }
        return left;
    }

    /**
     * Parses additive expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseAdditive() {
        Expression left = parseMultiplicative();
        while (check(PLUS) || check(MINUS)) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression right = parseMultiplicative();
            left = new BinaryExpression(left, op, right, line);
        }
        return left;
    }

    /**
     * Parses multiplicative expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseMultiplicative() {
        Expression left = parseUnary();
        while (check(STAR) || check(SLASH) || check(PERCENT)) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression right = parseUnary();
            left = new BinaryExpression(left, op, right, line);
        }
        return left;
    }

    /**
     * Parses unary expressions.
     *
     * @return the expression
     */
    private @NotNull Expression parseUnary() {
        if (check(INCREMENT) || check(DECREMENT)) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression operand = parseUnary();
            return new UnaryExpression(op, operand, true, line);
        }
        if (check(PLUS) || check(MINUS) || check(TILDE) || check(EXCLAMATION)) {
            int line = current().line();
            String op = current().value();
            advance();
            Expression operand = parseUnary();
            return new UnaryExpression(op, operand, true, line);
        }
        if (check(LEFT_PAREN) && isCast()) {
            return parseCast();
        }
        return parsePostfix();
    }

    /**
     * Determines whether the current parenthesized expression is a cast.
     *
     * @return true if this looks like a cast expression
     */
    private boolean isCast() {
        int saved = pos;
        try {
            advance();
            if (current().type().isPrimitive()) {
                parseType();
                if (check(RIGHT_PAREN)) {
                    return true;
                }
            }
            pos = saved;
            advance();
            parseType();
            if (check(RIGHT_PAREN)) {
                TokenType next = peek().type();
                return next == IDENTIFIER || next == LEFT_PAREN || next == THIS ||
                        next == SUPER || next == NEW || next == EXCLAMATION ||
                        next == TILDE || next == INCREMENT || next == DECREMENT ||
                        next == INT_LITERAL || next == LONG_LITERAL ||
                        next == FLOAT_LITERAL || next == DOUBLE_LITERAL ||
                        next == CHAR_LITERAL || next == STRING_LITERAL ||
                        next == TRUE || next == FALSE || next == NULL ||
                        next == TEXT_BLOCK || next == INT || next == LONG ||
                        next == FLOAT || next == DOUBLE || next == BOOLEAN ||
                        next == CHAR || next == BYTE || next == SHORT || next == VOID;
            }
            return false;
        } catch (ParserException e) {
            return false;
        } finally {
            pos = saved;
        }
    }

    /**
     * Parses a cast expression.
     *
     * @return the cast expression
     */
    private @NotNull CastExpression parseCast() {
        int line = current().line();
        expect(LEFT_PAREN);
        TypeNode type = parseType();
        expect(RIGHT_PAREN);
        Expression expr = parseUnary();
        return new CastExpression(type, expr, line);
    }

    /**
     * Parses postfix expressions (method calls, field access, array access, ++, --).
     *
     * @return the expression
     */
    private @NotNull Expression parsePostfix() {
        Expression expr = parsePrimary();
        while (true) {
            if (check(DOT)) {
                advance();
                if (check(NEW)) {
                    advance();
                    expr = parseQualifiedNew();
                } else if (check(CLASS)) {
                    int line = current().line();
                    advance();
                    expr = new FieldAccessExpression(expr, "class", line);
                } else if (check(THIS)) {
                    int line = current().line();
                    advance();
                    expr = new FieldAccessExpression(expr, "this", line);
                } else if (check(SUPER)) {
                    int line = current().line();
                    advance();
                    expr = new FieldAccessExpression(expr, "super", line);
                } else {
                    int line = current().line();
                    String name = expectIdentifier();
                    if (check(LEFT_PAREN)) {
                        List<Expression> args = parseArguments();
                        expr = new MethodCallExpression(expr, name, args, null, line);
                    } else {
                        expr = new FieldAccessExpression(expr, name, line);
                    }
                }
            } else if (check(LEFT_BRACKET)) {
                int line = current().line();
                advance();
                Expression index = parseExpression();
                expect(RIGHT_BRACKET);
                expr = new ArrayAccessExpression(expr, index, line);
            } else if (check(INCREMENT) || check(DECREMENT)) {
                int line = current().line();
                String op = current().value();
                advance();
                expr = new UnaryExpression(op, expr, false, line);
            } else if (check(DOUBLE_COLON)) {
                int line = current().line();
                advance();
                String name;
                if (check(NEW)) {
                    name = "new";
                    advance();
                } else {
                    name = expectIdentifier();
                }
                expr = new MethodReferenceExpression(expr, name, line);
            } else {
                break;
            }
        }
        return expr;
    }

    /**
     * Parses a qualified new expression (e.g., outer.new Inner()).
     *
     * @return the new expression
     */
    private @NotNull Expression parseQualifiedNew() {
        int line = current().line();
        TypeNode type = parseType();
        List<Expression> args = parseArguments();
        List<AstNode> anonBody = null;
        if (check(LEFT_BRACE)) {
            anonBody = parseAnonymousClassBody();
        }
        return new NewExpression(type, args, anonBody, line);
    }

    /**
     * Parses a primary expression.
     *
     * @return the expression
     */
    private @NotNull Expression parsePrimary() {
        int line = current().line();

        switch (current().type()) {
            case INT_LITERAL:
            case LONG_LITERAL:
            case FLOAT_LITERAL:
            case DOUBLE_LITERAL:
            case CHAR_LITERAL:
            case STRING_LITERAL:
            case TEXT_BLOCK, TRUE, FALSE: {
                Token tok = current();
                advance();
                return new LiteralExpression(tok.type(), tok.value(), line);
            }
            case NULL: {
                advance();
                return new LiteralExpression(NULL, "null", line);
            }
            case THIS: {
                advance();
                if (check(LEFT_PAREN)) {
                    List<Expression> args = parseArguments();
                    return new MethodCallExpression(null, "this", args, null, line);
                }
                return new ThisExpression(line);
            }
            case SUPER: {
                advance();
                if (check(LEFT_PAREN)) {
                    List<Expression> args = parseArguments();
                    return new MethodCallExpression(null, "super", args, null, line);
                }
                if (check(DOT)) {
                    advance();
                    String name = expectIdentifier();
                    if (check(LEFT_PAREN)) {
                        List<Expression> args = parseArguments();
                        return new MethodCallExpression(new SuperExpression(line), name, args, null, line);
                    }
                    return new FieldAccessExpression(new SuperExpression(line), name, line);
                }
                return new SuperExpression(line);
            }
            case NEW: {
                advance();
                return parseNewExpression(line);
            }
            case LEFT_PAREN: {
                if (isLambda()) {
                    return parseLambdaExpression(line);
                }
                advance();
                Expression expr = parseExpression();
                expect(RIGHT_PAREN);
                return new ParenExpression(expr, line);
            }
            case SWITCH: {
                return parseSwitchExpr();
            }
            case IDENTIFIER, VAR, YIELD, RECORD: {
                String name = current().value();
                if (peek().type() == ARROW) {
                    return parseSingleParamLambda(name, line);
                }
                advance();
                if (check(LEFT_PAREN)) {
                    List<Expression> args = parseArguments();
                    return new MethodCallExpression(null, name, args, null, line);
                }
                return new NameExpression(name, line);
            }
            default:
                if (current().type().isPrimitive() || current().type() == VOID) {
                    TypeNode type = parseType();
                    if (check(DOT) && peek().type() == CLASS) {
                        advance();
                        advance();
                        return new FieldAccessExpression(new NameExpression(type.toString(), line), "class", line);
                    }
                }
                throw error("Expected expression, found: " + current().type());
        }
    }

    /**
     * Parses a new expression (object or array creation).
     *
     * @param line the line of the new keyword
     * @return the new expression
     */
    private @NotNull Expression parseNewExpression(int line) {
        TypeNode type = parseType();
        if (check(LEFT_BRACKET)) {
            return parseNewArray(type, line);
        }
        if (check(LEFT_BRACE) && type.arrayDimensions() > 0) {
            ArrayInitializerExpression init = parseArrayInitializer();
            return new NewArrayExpression(new TypeNode(type.name(), type.typeArguments(), 0, type.line()), Collections.emptyList(), type.arrayDimensions(), init, line);
        }
        List<Expression> args = parseArguments();
        List<AstNode> anonBody = null;
        if (check(LEFT_BRACE)) {
            anonBody = parseAnonymousClassBody();
        }
        return new NewExpression(type, args, anonBody, line);
    }

    /**
     * Parses a new array expression.
     *
     * @param elementType the element type
     * @param line        the line of the new keyword
     * @return the new array expression
     */
    private @NotNull NewArrayExpression parseNewArray(@NotNull TypeNode elementType, int line) {
        List<Expression> dimExprs = new ArrayList<>();
        int extraDims = 0;
        while (check(LEFT_BRACKET)) {
            advance();
            if (check(RIGHT_BRACKET)) {
                advance();
                extraDims++;
                while (check(LEFT_BRACKET) && peek().type() == RIGHT_BRACKET) {
                    advance();
                    advance();
                    extraDims++;
                }
                break;
            } else {
                dimExprs.add(parseExpression());
                expect(RIGHT_BRACKET);
            }
        }
        ArrayInitializerExpression init = null;
        if (check(LEFT_BRACE)) {
            init = parseArrayInitializer();
        }
        return new NewArrayExpression(elementType, dimExprs, extraDims, init, line);
    }

    /**
     * Parses a switch expression.
     *
     * @return the switch expression
     */
    private @NotNull SwitchExpression parseSwitchExpr() {
        int line = current().line();
        expect(SWITCH);
        expect(LEFT_PAREN);
        Expression selector = parseExpression();
        expect(RIGHT_PAREN);
        expect(LEFT_BRACE);
        List<SwitchCase> cases = parseSwitchCases();
        expect(RIGHT_BRACE);
        return new SwitchExpression(selector, cases, line);
    }

    /**
     * Checks if the current position starts a lambda expression with parenthesized params.
     *
     * @return true if the tokens form a lambda expression
     */
    private boolean isLambda() {
        int saved = pos;
        try {
            advance();
            if (check(RIGHT_PAREN)) {
                advance();
                return check(ARROW);
            }
            skipLambdaParams();
            if (!check(RIGHT_PAREN)) return false;
            advance();
            return check(ARROW);
        } catch (ParserException e) {
            return false;
        } finally {
            pos = saved;
        }
    }

    /**
     * Skips tokens that look like lambda parameter declarations for lookahead purposes.
     */
    private void skipLambdaParams() {
        skipSingleLambdaParam();
        while (check(COMMA)) {
            advance();
            skipSingleLambdaParam();
        }
    }

    /**
     * Skips a single lambda parameter (possibly typed or untyped) for lookahead.
     */
    private void skipSingleLambdaParam() {
        parseAnnotations();
        parseModifiers();
        if (check(IDENTIFIER) || check(VAR)) {
            TokenType nextTok = peek().type();
            if (nextTok == COMMA || nextTok == RIGHT_PAREN) {
                advance();
                return;
            }
        }
        parseType();
        if (check(IDENTIFIER)) advance();
    }

    /**
     * Parses a parenthesized lambda expression.
     *
     * @param line the line of the opening parenthesis
     * @return the lambda expression
     */
    private @NotNull LambdaExpression parseLambdaExpression(int line) {
        expect(LEFT_PAREN);
        List<Parameter> params = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            params.add(parseLambdaParam());
            while (check(COMMA)) {
                advance();
                params.add(parseLambdaParam());
            }
        }
        expect(RIGHT_PAREN);
        expect(ARROW);
        return finishLambda(params, line);
    }

    /**
     * Parses a single lambda parameter.
     *
     * @return the parameter
     */
    private @NotNull Parameter parseLambdaParam() {
        List<AnnotationNode> annotations = parseAnnotations();
        int modifiers = parseModifiers();
        if (check(IDENTIFIER) || check(VAR)) {
            TokenType nextTok = peek().type();
            if (nextTok == COMMA || nextTok == RIGHT_PAREN) {
                String name = current().value();
                advance();
                return new Parameter(new TypeNode("var", List.of(), 0, current().line()), name, modifiers, annotations, false);
            }
        }
        TypeNode type = parseType();
        String name = expectIdentifier();
        return new Parameter(type, name, modifiers, annotations, false);
    }

    /**
     * Parses a single identifier lambda expression (e.g. x -> x + 1).
     *
     * @param name the parameter name
     * @param line the source line number
     * @return the lambda expression
     */
    private @NotNull LambdaExpression parseSingleParamLambda(@NotNull String name, int line) {
        advance();
        expect(ARROW);
        List<Parameter> params = List.of(new Parameter(new TypeNode("var", List.of(), 0, line), name, 0, List.of(), false));
        return finishLambda(params, line);
    }

    /**
     * Finishes parsing a lambda body after the arrow.
     *
     * @param params the lambda parameters
     * @param line   the source line number
     * @return the lambda expression
     */
    private @NotNull LambdaExpression finishLambda(@NotNull List<Parameter> params, int line) {
        if (check(LEFT_BRACE)) {
            Statement body = parseBlock();
            return new LambdaExpression(params, body, null, line);
        }
        Expression exprBody = parseExpression();
        return new LambdaExpression(params, null, exprBody, line);
    }

    /**
     * Parses method call arguments.
     *
     * @return the list of argument expressions
     */
    private @NotNull List<Expression> parseArguments() {
        expect(LEFT_PAREN);
        List<Expression> args = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            args.add(parseExpression());
            while (check(COMMA)) {
                advance();
                args.add(parseExpression());
            }
        }
        expect(RIGHT_PAREN);
        return args;
    }

    /**
     * Parses a dot separated qualified name.
     *
     * @return the qualified name string
     */
    private @NotNull String parseQualifiedName() {
        StringBuilder sb = new StringBuilder(expectIdentifier());
        while (check(DOT) && isIdentifierLike(peek().type())) {
            advance();
            sb.append('.').append(expectIdentifier());
        }
        return sb.toString();
    }

    /**
     * @return the current token
     */
    private @NotNull Token current() {
        return tokens[pos];
    }

    /**
     * Looks ahead by one token without consuming it.
     *
     * @return the token after the current one
     */
    private @NotNull Token peek() {
        int idx = pos + 1;
        if (idx >= tokenCount) return tokens[tokenCount - 1];
        return tokens[idx];
    }

    /**
     * Checks if the current token matches the given type.
     *
     * @param type the expected type
     * @return true if the current token matches
     */
    private boolean check(@NotNull TokenType type) {
        return current().type() == type;
    }

    /**
     * Advances to the next token.
     */
    private void advance() {
        if (pos < tokenCount - 1) {
            pos++;
        }
    }

    /**
     * Inserts a freshly split token mid-stream when we need to break a
     * compound operator like {@code >>} back into separate {@code >}s for
     * type-argument parsing. Grows the backing array only when needed.
     */
    private void insertTokenAt(int index, @NotNull Token token) {
        if (tokenCount == tokens.length) {
            Token[] grown = new Token[tokens.length + 4];
            System.arraycopy(tokens, 0, grown, 0, tokenCount);
            tokens = grown;
        }
        System.arraycopy(tokens, index, tokens, index + 1, tokenCount - index);
        tokens[index] = token;
        tokenCount++;
    }

    /**
     * Expects the current token to be the given type and advances.
     *
     * @param type the expected type
     */
    private void expect(@NotNull TokenType type) {
        if (!check(type)) {
            throw error("Expected " + type + " but found " + current().type() + " '" + current().value() + "'");
        }
        advance();
    }

    /**
     * Expects a closing '&gt;' for type arguments, handling the &gt;&gt; and &gt;&gt;&gt; ambiguity.
     * When the lexer produces RIGHT_SHIFT (&gt;&gt;) or UNSIGNED_RIGHT_SHIFT (&gt;&gt;&gt;),
     * this method splits them so one '&gt;' is consumed and the rest is pushed back.
     */
    private void expectTypeArgumentClose() {
        if (check(GREATER)) {
            advance();
        } else if (check(RIGHT_SHIFT)) {
            Token tok = current();
            tokens[pos] = new Token(GREATER, ">", tok.line(), tok.column());
            insertTokenAt(pos + 1, new Token(GREATER, ">", tok.line(), tok.column() + 1));
            advance();
        } else if (check(UNSIGNED_RIGHT_SHIFT)) {
            Token tok = current();
            tokens[pos] = new Token(GREATER, ">", tok.line(), tok.column());
            insertTokenAt(pos + 1, new Token(RIGHT_SHIFT, ">>", tok.line(), tok.column() + 1));
            advance();
        } else {
            throw error("Expected GREATER but found " + current().type() + " '" + current().value() + "'");
        }
    }

    /**
     * Expects an identifier token and returns its value.
     *
     * @return the identifier string
     */
    private @NotNull String expectIdentifier() {
        if (!check(IDENTIFIER) && !check(RECORD) && !check(VAR) && !check(YIELD)) {
            throw error("Expected identifier but found " + current().type() + " '" + current().value() + "'");
        }
        String value = current().value();
        advance();
        return value;
    }

    /**
     * Returns true if the token type can appear as a name segment in a qualified name.
     * Contextual keywords like {@code record}, {@code var}, and {@code yield} are valid identifiers in dotted paths.
     */
    private boolean isIdentifierLike(@NotNull TokenType type) {
        return type == IDENTIFIER || type == RECORD || type == VAR || type == YIELD;
    }

    /**
     * Creates a parser exception at the current position.
     *
     * @param message the error message
     * @return the parser exception
     */
    private @NotNull ParserException error(@NotNull String message) {
        Token tok = current();
        return new ParserException(message, tok.line(), tok.column());
    }
}
