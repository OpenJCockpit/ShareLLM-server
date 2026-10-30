package io.sharellm.server.adapter.inbound.web.support;

import io.sharellm.server.domain.exception.AuthenticationException;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.exception.LeaseConflictException;
import io.sharellm.server.domain.exception.PayloadTooLargeException;
import io.sharellm.server.domain.exception.QueueFullException;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.exception.ShareLlmException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates application errors into {@code {"error": "..."}} responses. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ShareLlmException.class)
    public ResponseEntity<Map<String, String>> rejected(ShareLlmException error) {
        return ResponseEntity.status(statusOf(error)).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<Map<String, String>> interrupted(InterruptedException error) {
        Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Interrupted while awaiting result"));
    }

    static HttpStatus statusOf(ShareLlmException error) {
        return switch (error) {
            case InvalidRequestException _ -> HttpStatus.BAD_REQUEST;
            case AuthenticationException _ -> HttpStatus.UNAUTHORIZED;
            case ResourceNotFoundException _ -> HttpStatus.NOT_FOUND;
            case LeaseConflictException _ -> HttpStatus.CONFLICT;
            case PayloadTooLargeException _ -> HttpStatus.CONTENT_TOO_LARGE;
            case QueueFullException _ -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }
}
