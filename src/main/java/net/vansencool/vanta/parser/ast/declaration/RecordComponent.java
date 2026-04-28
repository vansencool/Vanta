package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a single record component (e.g., int x).
 *
 * @param type        the component type
 * @param name        the component name
 * @param annotations the annotations on this component
 */
public record RecordComponent(@NotNull TypeNode type, @NotNull String name, @NotNull List<AnnotationNode> annotations) {
}
