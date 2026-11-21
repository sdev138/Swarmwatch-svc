package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.provider.Secrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class KnowledgeConfiguration {
    @Bean @Profile("local") public KnowledgeResources localKnowledgeResources() { return new LocalKnowledgeResources(); }
    @Bean @Profile("!local") public KnowledgeResources openAiKnowledgeResources(HttpClient client, ObjectMapper json, Secrets secrets,
            @Value("${swarmwatch.openai-base-url}") String base) {
        return new OpenAiKnowledgeResources(client, json, secrets, base);
    }
}
