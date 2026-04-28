package net.vansencool.vanta.parser.ast.declaration;

import org.jetbrains.annotations.NotNull;

/**
 * Represents an import declaration.
 *
 * @param name       the fully qualified import name
 * @param isStatic   true if this is a static import
 * @param isWildcard true if this is a wildcard (on demand) import
 * @param line       the source line number
 */
public record ImportDeclaration(@NotNull String name, boolean isStatic, boolean isWildcard, int line) {

}
