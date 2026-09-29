package com.lumira.platform.common;

import java.util.List;

/** Shared, one-based pagination contract for platform list endpoints. */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {}
