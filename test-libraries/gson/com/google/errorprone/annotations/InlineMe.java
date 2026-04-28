package com.google.errorprone.annotations;
public @interface InlineMe {
    String replacement();
    String[] imports() default {};
    String[] staticImports() default {};
}
