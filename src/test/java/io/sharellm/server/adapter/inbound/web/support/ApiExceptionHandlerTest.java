package io.sharellm.server.adapter.inbound.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.sharellm.server.domain.exception.AuthenticationException;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.exception.LeaseConflictException;
import io.sharellm.server.domain.exception.PayloadTooLargeException;
import io.sharellm.server.domain.exception.QueueFullException;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.exception.ShareLlmException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new InvalidRequestException("bad"), HttpStatus.BAD_REQUEST),
                Arguments.of(new AuthenticationException("who"), HttpStatus.UNAUTHORIZED),
                Arguments.of(new ResourceNotFoundException("gone"), HttpStatus.NOT_FOUND),
                Arguments.of(new LeaseConflictException("stale"), HttpStatus.CONFLICT),
                Arguments.of(new PayloadTooLargeException("big"), HttpStatus.CONTENT_TOO_LARGE),
                Arguments.of(new QueueFullException("full"), HttpStatus.TOO_MANY_REQUESTS));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void mapsEachApplicationErrorToItsStatus(ShareLlmException error, HttpStatus status) {
        ResponseEntity<Map<String, String>> response = handler.rejected(error);
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("error", error.getMessage());
    }

    @Test
    void interruptionIsUnavailableAndRestoresTheInterruptFlag() {
        try {
            ResponseEntity<Map<String, String>> response = handler.interrupted(new InterruptedException());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
