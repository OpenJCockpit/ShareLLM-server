package io.sharellm.server;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class ShareLlmServerApplicationTest {
    @Test
    void mainBootsTheSpringApplication() {
        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            String[] args = {"--server.port=0"};
            ShareLlmServerApplication.main(args);
            spring.verify(() -> SpringApplication.run(ShareLlmServerApplication.class, args));
        }
    }
}
