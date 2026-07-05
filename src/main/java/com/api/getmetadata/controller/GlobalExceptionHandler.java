package com.api.getmetadata.controller;

import com.api.getmetadata.python.PythonExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * REST API 공통 예외 처리. 오류를 일관된 JSON 형태로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 잘못된 입력 (빈 값 등). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Python 실행/추출 오류. */
    @ExceptionHandler(PythonExecutionException.class)
    public ResponseEntity<Map<String, Object>> handlePython(PythonExecutionException e) {
        log.warn("Python 추출 오류: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "status", status.value(),
                "error", message == null ? status.getReasonPhrase() : message
        ));
    }
}
