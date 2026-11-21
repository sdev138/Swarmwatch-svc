package com.athnasia.swarmwatchsvc.provider;

import com.google.cloud.secretmanager.v1.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CloudSecrets implements Secrets {
    private final String project;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    public CloudSecrets(String project) { this.project = project; }
    private String read(String name) {
        return values.computeIfAbsent(name, ignored -> {
            try (var client = SecretManagerServiceClient.create()) {
                String value = client.accessSecretVersion(SecretVersionName.of(project, name, "latest")).getPayload().getData().toStringUtf8().trim();
                if (value.isBlank()) throw new IllegalStateException();
                return value;
            } catch (Exception failure) { throw new ProviderException("Required provider secret is unavailable. Check ADC and Secret Manager access."); }
        });
    }
    public String openAiKey() { return read("openai-secret"); }
    public String inkboxKey() { return read("inkbox-secret"); }
}
