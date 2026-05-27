package com.nnu.rasterapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUpload(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.PAYLOAD_TOO_LARGE;
        String message = "上传体积超过限制（请缩小文件或在服务端调大 spring.servlet.multipart.max-*）";
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        Instant.now().toString(),
                        status.value(),
                        status.getReasonPhrase(),
                        message,
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String detail = ex.getMessage() == null || ex.getMessage().isBlank() ? "multipart 解析失败" : ex.getMessage();
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        Instant.now().toString(),
                        status.value(),
                        status.getReasonPhrase(),
                        detail,
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = resolveResponseStatusMessage(ex, status);
        log.warn("{} {} — {}", status.value(), request.getRequestURI(), message);
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        Instant.now().toString(),
                        status.value(),
                        status.getReasonPhrase(),
                        message,
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        Instant.now().toString(),
                        status.value(),
                        status.getReasonPhrase(),
                        ex.getMessage() == null || ex.getMessage().isBlank() ? "服务器内部错误" : ex.getMessage(),
                        request.getRequestURI()
                )
        );
    }

    /** Spring 6：部分 ResponseStatusException 把文案放在 ProblemDetail 而非 getReason() */
    private static String resolveResponseStatusMessage(ResponseStatusException ex, HttpStatus status) {
        String reason = ex.getReason();
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        ProblemDetail body = ex.getBody();
        if (body != null) {
            String detail = body.getDetail();
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            String title = body.getTitle();
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return status.getReasonPhrase();
    }

    public record ApiErrorResponse(
            String timestamp,
            int status,
            String error,
            String message,
            String path
    ) {
    }
}
