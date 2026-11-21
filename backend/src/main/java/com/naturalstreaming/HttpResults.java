package com.naturalstreaming;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RestControllerAdvice
public class HttpResults {
    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> rejected(ResponseStatusException error) {
        String reason = error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", reason == null ? "request_failed" : reason));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> malformed(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage() == null ? "invalid_input" : error.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> oversized() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "upload_too_large"));
    }

    static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }
}
