package com.lumira.platform.common;

import java.util.List;

/** 平台列表接口共用的从 1 开始分页契约。 Shared, one-based pagination contract for platform list endpoints. */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {
    public <R> PageResult<R> map(java.util.function.Function<? super T, ? extends R> convert) {
        return new PageResult<R>(items.stream().<R>map(convert).toList(), total, page, pageSize);
    }
}
