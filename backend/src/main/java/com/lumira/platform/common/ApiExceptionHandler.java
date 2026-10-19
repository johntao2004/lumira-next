package com.lumira.platform.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Return API errors directly instead of forwarding through the protected error route. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(RateLimitExceededException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> rateLimit(
            RateLimitExceededException error) {
        String retryAfter = "passwordHashCapacity".equals(error.getMessage()) ? "1" : "60";
        return org.springframework.http.ResponseEntity.status(429)
                .header("Retry-After", retryAfter)
                .body(
                        ProblemDetail.forStatusAndDetail(
                                HttpStatus.TOO_MANY_REQUESTS, error.getMessage()));
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessException.class,
        org.springframework.transaction.TransactionException.class,
        java.io.IOException.class
    })
    public ProblemDetail infrastructure(Exception error) {
        if (error instanceof java.io.IOException) {
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause())
                if (cause instanceof java.net.SocketTimeoutException)
                    return ProblemDetail.forStatusAndDetail(
                            HttpStatus.REQUEST_TIMEOUT, "uploadTimeout");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "unavailable");
    }

    @ExceptionHandler(ImportValidationException.class)
    public ProblemDetail importValidation(ImportValidationException error) {
        var problem = ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason());
        problem.setProperty("issues", error.issues());
        return problem;
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ProblemDetail duplicate(org.springframework.dao.DuplicateKeyException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "duplicateRecord");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> status(
            ResponseStatusException error) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        error.getStatusCode(),
                        error.getReason() == null ? "Request failed" : error.getReason());
        var response = org.springframework.http.ResponseEntity.status(error.getStatusCode());
        if ("passwordHashCapacity".equals(error.getReason())) response.header("Retry-After", "1");
        return response.body(problem);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail invalidBody(
            org.springframework.http.converter.HttpMessageNotReadableException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalidRequest");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail invalidParameter(MethodArgumentTypeMismatchException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalidRequest");
    }

    @ExceptionHandler({
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.web.bind.MethodArgumentNotValidException.class
    })
    public ProblemDetail missingRequest(Exception error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalidRequest");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ProblemDetail unsupportedMedia(
            org.springframework.web.HttpMediaTypeNotSupportedException error) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "invalidRequest");
    }
}
