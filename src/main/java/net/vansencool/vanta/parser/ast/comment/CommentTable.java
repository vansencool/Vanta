package net.vansencool.vanta.parser.ast.comment;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side table mapping {@link AstNode}s to their attached source comments.
 *
 * <p>Carried on a parsed {@link CompilationUnit}
 * for library consumers that want to inspect or rewrite the original source.
 * Identity keyed so reused literal AST values stay distinct.
 */
public final class CommentTable {

    private final @NotNull Map<AstNode, List<Comment>> leading = new IdentityHashMap<>();
    private final @NotNull Map<AstNode, List<Comment>> trailing = new IdentityHashMap<>();
    private final @NotNull List<Comment> all;

    public CommentTable(@NotNull List<Comment> all) {
        this.all = List.copyOf(all);
    }

    /**
     * @return every comment seen by the lexer, in source order. Useful when
     * walking a file without caring which node owns each comment.
     */
    public @NotNull List<Comment> all() {
        return all;
    }

    /**
     * @param node target AST node
     * @return comments that appear immediately before {@code node}, in
     * source order. Empty when {@code node} has none.
     */
    public @NotNull List<Comment> leading(@NotNull AstNode node) {
        List<Comment> list = leading.get(node);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * @param node target AST node
     * @return comments that appear on the same line directly after the
     * {@code node}'s last token, in source order. Empty when none.
     */
    public @NotNull List<Comment> trailing(@NotNull AstNode node) {
        List<Comment> list = trailing.get(node);
        return list == null ? Collections.emptyList() : list;
    }

    public void attachLeading(@NotNull AstNode node, @NotNull List<Comment> comments) {
        if (comments.isEmpty()) return;
        leading.computeIfAbsent(node, k -> new ArrayList<>()).addAll(comments);
    }

    public void attachTrailing(@NotNull AstNode node, @Nullable Comment comment) {
        if (comment == null) return;
        trailing.computeIfAbsent(node, k -> new ArrayList<>()).add(comment);
    }
}
