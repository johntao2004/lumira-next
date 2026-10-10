package com.lumira.platform.common;

import java.lang.annotation.*;

/** Policy configuration must be able to contain the words it manages. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ContentPolicyExempt {}
