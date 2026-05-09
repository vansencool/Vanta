package net.vansencool.vanta.symbol.annotation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public interface AnnotationInstance {

    @NotNull String internalName();

    /**
     * Attribute values keyed by attribute name. Values use the same shape
     * ASM's annotation visitor accepts: boxed primitives, {@code String},
     * arrays of those for array entries, or nested {@code AnnotationInstance}.
     */
    @NotNull Map<String, Object> values();

    /**
     * Retention policy when the annotation type is reachable, otherwise
     * {@code null}. Source backed annotations cannot read this without
     * resolving the annotation type and so return {@code null}.
     */
    @Nullable RetentionPolicy retention();
}
