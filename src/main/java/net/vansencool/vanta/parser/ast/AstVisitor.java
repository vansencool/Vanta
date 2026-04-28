package net.vansencool.vanta.parser.ast;

import net.vansencool.vanta.parser.ast.declaration.AnnotationNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
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
import net.vansencool.vanta.parser.ast.statement.ContinueStatement;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
import net.vansencool.vanta.parser.ast.statement.LabeledStatement;
import net.vansencool.vanta.parser.ast.statement.ReturnStatement;
import net.vansencool.vanta.parser.ast.statement.SwitchStatement;
import net.vansencool.vanta.parser.ast.statement.SynchronizedStatement;
import net.vansencool.vanta.parser.ast.statement.ThrowStatement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarationStatement;
import net.vansencool.vanta.parser.ast.statement.WhileStatement;
import net.vansencool.vanta.parser.ast.statement.YieldStatement;
import org.jetbrains.annotations.NotNull;

/**
 * Visitor interface for traversing the AST.
 * Contains visit methods for every concrete AST node type.
 */
public interface AstVisitor {

    /**
     * Visits a compilation unit (top level file).
     *
     * @param node the compilation unit
     */
    void visit(@NotNull CompilationUnit node);

    /**
     * Visits a class declaration.
     *
     * @param node the class declaration
     */
    void visit(@NotNull ClassDeclaration node);

    /**
     * Visits a method declaration.
     *
     * @param node the method declaration
     */
    void visit(@NotNull MethodDeclaration node);

    /**
     * Visits a field declaration.
     *
     * @param node the field declaration
     */
    void visit(@NotNull FieldDeclaration node);

    /**
     * Visits a block statement.
     *
     * @param node the block
     */
    void visit(@NotNull BlockStatement node);

    /**
     * Visits a return statement.
     *
     * @param node the return statement
     */
    void visit(@NotNull ReturnStatement node);

    /**
     * Visits an if statement.
     *
     * @param node the if statement
     */
    void visit(@NotNull IfStatement node);

    /**
     * Visits a while statement.
     *
     * @param node the while statement
     */
    void visit(@NotNull WhileStatement node);

    /**
     * Visits a do while statement.
     *
     * @param node the do while statement
     */
    void visit(@NotNull DoWhileStatement node);

    /**
     * Visits a for statement.
     *
     * @param node the for statement
     */
    void visit(@NotNull ForStatement node);

    /**
     * Visits an enhanced for statement.
     *
     * @param node the enhanced for statement
     */
    void visit(@NotNull ForEachStatement node);

    /**
     * Visits a switch statement.
     *
     * @param node the switch statement
     */
    void visit(@NotNull SwitchStatement node);

    /**
     * Visits a try statement.
     *
     * @param node the try statement
     */
    void visit(@NotNull TryStatement node);

    /**
     * Visits a throw statement.
     *
     * @param node the throw statement
     */
    void visit(@NotNull ThrowStatement node);

    /**
     * Visits a break statement.
     *
     * @param node the break statement
     */
    void visit(@NotNull BreakStatement node);

    /**
     * Visits a continue statement.
     *
     * @param node the continue statement
     */
    void visit(@NotNull ContinueStatement node);

    /**
     * Visits a local variable declaration statement.
     *
     * @param node the variable declaration
     */
    void visit(@NotNull VariableDeclarationStatement node);

    /**
     * Visits an expression statement.
     *
     * @param node the expression statement
     */
    void visit(@NotNull ExpressionStatement node);

    /**
     * Visits a yield statement.
     *
     * @param node the yield statement
     */
    void visit(@NotNull YieldStatement node);

    /**
     * Visits a labeled statement.
     *
     * @param node the labeled statement
     */
    void visit(@NotNull LabeledStatement node);

    /**
     * Visits a binary expression.
     *
     * @param node the binary expression
     */
    void visit(@NotNull BinaryExpression node);

    /**
     * Visits a unary expression.
     *
     * @param node the unary expression
     */
    void visit(@NotNull UnaryExpression node);

    /**
     * Visits a literal expression.
     *
     * @param node the literal expression
     */
    void visit(@NotNull LiteralExpression node);

    /**
     * Visits an identifier (name reference) expression.
     *
     * @param node the name expression
     */
    void visit(@NotNull NameExpression node);

    /**
     * Visits a method call expression.
     *
     * @param node the method call expression
     */
    void visit(@NotNull MethodCallExpression node);

    /**
     * Visits a field access expression.
     *
     * @param node the field access expression
     */
    void visit(@NotNull FieldAccessExpression node);

    /**
     * Visits an array access expression.
     *
     * @param node the array access expression
     */
    void visit(@NotNull ArrayAccessExpression node);

    /**
     * Visits a new (object creation) expression.
     *
     * @param node the new expression
     */
    void visit(@NotNull NewExpression node);

    /**
     * Visits a new array expression.
     *
     * @param node the new array expression
     */
    void visit(@NotNull NewArrayExpression node);

    /**
     * Visits a cast expression.
     *
     * @param node the cast expression
     */
    void visit(@NotNull CastExpression node);

    /**
     * Visits an instanceof expression.
     *
     * @param node the instanceof expression
     */
    void visit(@NotNull InstanceofExpression node);

    /**
     * Visits a ternary (conditional) expression.
     *
     * @param node the ternary expression
     */
    void visit(@NotNull TernaryExpression node);

    /**
     * Visits an assignment expression.
     *
     * @param node the assignment expression
     */
    void visit(@NotNull AssignmentExpression node);

    /**
     * Visits a this expression.
     *
     * @param node the this expression
     */
    void visit(@NotNull ThisExpression node);

    /**
     * Visits a super expression.
     *
     * @param node the super expression
     */
    void visit(@NotNull SuperExpression node);

    /**
     * Visits a parenthesized expression.
     *
     * @param node the parenthesized expression
     */
    void visit(@NotNull ParenExpression node);

    /**
     * Visits an array initializer expression.
     *
     * @param node the array initializer
     */
    void visit(@NotNull ArrayInitializerExpression node);

    /**
     * Visits a switch expression.
     *
     * @param node the switch expression
     */
    void visit(@NotNull SwitchExpression node);

    /**
     * Visits an annotation node.
     *
     * @param node the annotation
     */
    void visit(@NotNull AnnotationNode node);

    /**
     * Visits an enum constant.
     *
     * @param node the enum constant
     */
    void visit(@NotNull EnumConstant node);

    /**
     * Visits a synchronized statement.
     *
     * @param node the synchronized statement
     */
    void visit(@NotNull SynchronizedStatement node);

    /**
     * Visits an assert statement.
     *
     * @param node the assert statement
     */
    void visit(@NotNull AssertStatement node);

    /**
     * Visits a lambda expression.
     *
     * @param node the lambda expression
     */
    void visit(@NotNull LambdaExpression node);

    /**
     * Visits a method reference expression.
     *
     * @param node the method reference expression
     */
    void visit(@NotNull MethodReferenceExpression node);
}
