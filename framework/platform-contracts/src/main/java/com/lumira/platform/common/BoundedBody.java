package com.lumira.platform.common;

import java.lang.annotation.*;

/** 声明接口字节上限，在反序列化前执行限制。 Explicit per-endpoint byte budget, enforced before deserialization. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface BoundedBody {
    int maxBytes();

    String errorCode() default "requestBodyTooLarge";
}
