package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a resource declaration in a try-with-resources statement.
 * Example: {@code BufferedReader reader = new BufferedReader(new FileReader("file.txt"))}
 *
 * @param type        the resource type
 * @param name        the resource variable name
 * @param initializer the initializer expression
 * @param line        the source line number
 */
public record ResourceDeclaration(@NotNull TypeNode type, @NotNull String name, @NotNull Expression initializer,
                                  int line) {
}
