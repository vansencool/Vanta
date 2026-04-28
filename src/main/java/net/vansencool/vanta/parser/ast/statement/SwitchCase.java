package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a single case within a switch statement or expression.
 * Supports both traditional (colon) and arrow style cases.
 *
 * @param labels     the case labels, or null for default
 * @param statements the case body statements
 * @param isDefault  true if this is a default case
 * @param isArrow    true if this uses arrow syntax
 * @param line       the source line number
 */
public record SwitchCase(@Nullable List<Expression> labels, @NotNull List<Statement> statements, boolean isDefault,
                         boolean isArrow, int line) {

}
