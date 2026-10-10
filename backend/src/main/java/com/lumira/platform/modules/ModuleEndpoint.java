package com.lumira.platform.modules;

import java.lang.annotation.*;

/** Declare the module owning a controller or a specific HTTP handler. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleEndpoint {
    String value();
}
