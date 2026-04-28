package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a catch clause within a try statement.
 *
 * @param exceptionTypes the exception types (multi catch uses multiple)
 * @param variableName   the exception variable name
 * @param body           the catch body
 * @param line           the source line number
 */
public record CatchClause(@NotNull List<TypeNode> exceptionTypes, @NotNull String variableName,
                          @NotNull BlockStatement body, int line) {

}
