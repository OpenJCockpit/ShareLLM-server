package io.sharellm.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShareLlmServerApplication {
    public static void main(String[] args) { SpringApplication.run(ShareLlmServerApplication.class, args); }
}
