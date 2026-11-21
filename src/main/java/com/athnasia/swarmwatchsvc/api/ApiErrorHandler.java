package com.athnasia.swarmwatchsvc.api;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class) public ResponseEntity<Map<String, String>> api(ApiException error) { return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception error) { return ResponseEntity.badRequest().body(Map.of("message", "Check the required fields and submit valid JSON.")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String, String>> unavailable(Exception error) {
        LoggerFactory.getLogger(ApiErrorHandler.class).warn("API operation unavailable ({})", error.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("message", "The service could not complete this action. Check cloud configuration and retry."));
    }
}
