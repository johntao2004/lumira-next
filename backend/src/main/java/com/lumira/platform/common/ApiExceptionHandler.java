package com.lumira.platform.common;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Return API errors directly instead of forwarding through the protected error route. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(RateLimitExceededException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> rateLimit(RateLimitExceededException error){
        return org.springframework.http.ResponseEntity.status(429).header("Retry-After","60")
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,error.getMessage()));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,java.io.IOException.class})
    public ProblemDetail infrastructure(Exception error) {
        if(error instanceof java.io.IOException){
            Throwable cause=error;
            for(int depth=0;cause!=null&&depth<10;depth++,cause=cause.getCause())
                if(cause instanceof java.net.SocketTimeoutException)return ProblemDetail.forStatusAndDetail(HttpStatus.REQUEST_TIMEOUT,"uploadTimeout");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,"unavailable");
    }
    @ExceptionHandler(ImportValidationException.class)
    public ProblemDetail importValidation(ImportValidationException error) {
        var problem=ProblemDetail.forStatusAndDetail(error.getStatusCode(),error.getReason());
        problem.setProperty("issues",error.issues());
        return problem;
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ProblemDetail duplicate(org.springframework.dao.DuplicateKeyException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "duplicateRecord");
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException error) {
        return ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason() == null ? "Request failed" : error.getReason());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail invalidBody(org.springframework.http.converter.HttpMessageNotReadableException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalidRequest");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail invalidParameter(MethodArgumentTypeMismatchException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameter");
    }
}
