package com.athnasia.swarmwatchsvc.provider;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

final class ProviderHttp {
    private final HttpClient client;
    private final ObjectMapper json;
    ProviderHttp(HttpClient client, ObjectMapper json) { this.client = client; this.json = json; }
    JsonNode send(String provider, String method, String url, Map<String, String> headers, Object payload, boolean allowNotFound) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120));
            headers.forEach(builder::header);
            if (payload != null) builder.header("Content-Type", "application/json");
            var request = builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (allowNotFound && response.statusCode() == 404) return json.nullNode();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ProviderException(provider + " request failed (HTTP " + response.statusCode() + "). Check account permissions, quota, and configuration.");
            return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        } catch (ProviderException failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new ProviderException(provider + " request interrupted. Retry when the service is available."); }
        catch (Exception failure) { throw new ProviderException(provider + " request could not complete. Check connectivity and retry."); }
    }
}
