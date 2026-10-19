package com.lumira.platform.common;

import java.lang.annotation.*;

/** 内容策略配置必须允许包含其管理的词语。 Policy configuration must be able to contain the words it manages. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ContentPolicyExempt {}
