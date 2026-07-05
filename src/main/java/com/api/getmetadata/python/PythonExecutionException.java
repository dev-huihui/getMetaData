package com.api.getmetadata.python;

/**
 * Python 스크립트 실행/통신 중 발생한 오류를 표현하는 런타임 예외.
 */
public class PythonExecutionException extends RuntimeException {

    public PythonExecutionException(String message) {
        super(message);
    }

    public PythonExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
