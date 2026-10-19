package com.lumira.platform.modules;

import java.lang.annotation.*;

/** 声明控制器或具体 HTTP 处理方法所属的模块。 Declare the module owning a controller or a specific HTTP handler. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleEndpoint {
    String value();
}
