package com.lumira.platform.common;

/** 独立于页面和业务模块的内容策略契约。 Platform content policy, independent of any page or business module. */
public interface ContentPolicyPort {
    /** 检查文本并返回是否被策略阻止。 Evaluates text and returns whether policy blocks it. */
    ScanResult evaluate(String text);

    /**
     * 兼容实现只检查文本；需要操作者或来源策略的实现必须覆盖此方法。 The compatibility default checks text only;
     * actor/source-aware policies must override it.
     */
    default ScanResult evaluate(String actor, String text, String source) {
        return evaluate(text);
    }

    record ScanResult(boolean blocked) {}
}
