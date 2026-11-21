package com.athnasia.swarmwatchsvc.config;

import com.athnasia.swarmwatchsvc.auth.*;
import com.athnasia.swarmwatchsvc.provider.*;
import com.athnasia.swarmwatchsvc.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.datastore.*;
import java.net.http.HttpClient;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
@Profile("!local")
public class ProductionConfig {
    @Bean public Supplier<Datastore> datastoreSupplier(@Value("${swarmwatch.gcp-project-id}") String project) {
        return new Supplier<>() {
            private Datastore datastore;
            public synchronized Datastore get() {
                if (datastore == null) datastore = DatastoreOptions.newBuilder().setProjectId(project).build().getService();
                return datastore;
            }
        };
    }
    @Bean public SwarmRepository datastoreRepository(Supplier<Datastore> datastoreSupplier, ObjectMapper json) { return new DatastoreSwarmRepository(datastoreSupplier, json); }
    @Bean public IdentityVerifier googleVerifier(@Value("${swarmwatch.google-client-id}") String audience) { return new GoogleIdentityVerifier(audience); }
    @Bean public Secrets cloudSecrets(@Value("${swarmwatch.gcp-project-id}") String project) { return new CloudSecrets(project); }
    @Bean public PhoneProvider inkboxProvider(HttpClient client, ObjectMapper json, Secrets secrets, @Value("${swarmwatch.inkbox-base-url}") String base) { return new InkboxProvider(client, json, secrets, base); }
    @Bean public ModelProvider openAiProvider(HttpClient client, ObjectMapper json, Secrets secrets, @Value("${swarmwatch.openai-base-url}") String base) { return new OpenAiProvider(client, json, secrets, base); }
}
