package com.lumira.platform.common;

import java.lang.annotation.*;

/** Explicit per-endpoint byte budget, enforced before deserialization. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface BoundedBody {
    int maxBytes();

    String errorCode() default "requestBodyTooLarge";
}
