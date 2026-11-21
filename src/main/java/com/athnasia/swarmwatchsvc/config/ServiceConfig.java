package com.athnasia.swarmwatchsvc.config;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.auth.IdentityVerifier;
import com.athnasia.swarmwatchsvc.provider.*;
import com.athnasia.swarmwatchsvc.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.*;

@Configuration
public class ServiceConfig {
    @Bean public ObjectMapper providerObjectMapper() { return new ObjectMapper(); }
    @Bean public HttpClient providerHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean @Profile("local") public SwarmRepository localRepository(ObjectMapper json) { return new MemorySwarmRepository(json); }
    @Bean @Profile("local") public PhoneProvider localPhoneProvider() { return new LocalPhoneProvider(); }
    @Bean @Profile("local") public ModelProvider localModelProvider() { return new LocalModelProvider(); }
    @Bean @Profile("local") public IdentityVerifier localVerifier() {
        return token -> {
            if (!"local-development-token".equals(token)) throw new ApiException(401, "Use the explicit local development sign-in.");
            return new IdentityVerifier.Identity("local-google-subject", "Local developer (simulated)", "local@example.invalid");
        };
    }
    @Bean @Profile("local") public Secrets localSecrets() {
        return new Secrets() {
            public String openAiKey() { throw new IllegalStateException("Local mode does not access production secrets."); }
            public String inkboxKey() { throw new IllegalStateException("Local mode does not access production secrets."); }
        };
    }
}
