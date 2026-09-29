package com.lumira.platform.common;
public final class RateLimitExceededException extends RuntimeException {
 public RateLimitExceededException(String code){super(code);}
}
