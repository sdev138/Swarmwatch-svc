package com.athnasia.swarmwatchsvc.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class NandaClient implements Registrar {
    private static final String SENDER = "noreply@nandaindex.org";
    private static final Pattern TOKEN = Pattern.compile("https://(?:api\\.)?nandaindex\\.org/[^\\s\"'<>]*?[?&](?:amp;)?token=([a-fA-F0-9]{8,256})(?=[&#\\s\"'<>]|$)");
    private final HttpClient http;
    private final ObjectMapper json;
    private final URI nanda;
    private final URI inkbox;
    private final Supplier<String> inkboxKey;
    private final PasswordStore passwords;

    NandaClient(HttpClient http, ObjectMapper json, URI nanda, URI inkbox, Supplier<String> inkboxKey, PasswordStore passwords) {
        this.http = http;
        this.json = json;
        this.nanda = nanda;
        this.inkbox = inkbox;
        this.inkboxKey = inkboxKey;
        this.passwords = passwords;
    }

    @Override
    public Registration.Stage advance(Registration registration) {
        return switch (registration.stage()) {
            case ACCOUNT -> account(registration);
            case ACCOUNT_VERIFY -> verifyAccount(registration);
            case ENTRY -> createEntry(registration);
            case ENTRY_VERIFY -> verifyEntry(registration);
            case RESOLVE -> resolve(registration);
            default -> registration.stage();
        };
    }

    private Registration.Stage account(Registration registration) {
        Reply reply = nanda("POST", "/auth/register", null, Map.of("email", registration.email(),
                "password", passwords.passwordFor(registration), "display_name", registration.name()), "account creation");
        String token;
        if (reply.status() == 409) token = signIn(registration);
        else {
            requireSuccess(reply, "Nanda account creation");
            token = sessionToken(reply.body());
        }
        return me(token).path("email_verified").asBoolean(false) ? Registration.Stage.ENTRY : Registration.Stage.ACCOUNT_VERIFY;
    }

    private Registration.Stage verifyAccount(Registration registration) {
        String token = signIn(registration);
        if (me(token).path("email_verified").asBoolean(false)) return Registration.Stage.ENTRY;
        String verification = verificationToken(registration, "Verify your NANDA Index account email");
        if (verification == null) return Registration.Stage.ACCOUNT_VERIFY;
        requireSuccess(nanda("POST", "/auth/verify-account", null, Map.of("token", verification), "account verification"), "Nanda account verification");
        return me(token).path("email_verified").asBoolean(false) ? Registration.Stage.ENTRY : Registration.Stage.ACCOUNT_VERIFY;
    }

    private Registration.Stage createEntry(Registration registration) {
        String token = signIn(registration);
        String description = "Personal agent on Inkbox. Contact by phone: " + registration.phone() + ".";
        Reply reply = nanda("POST", "/api/v1/orgs", token, Map.of(
                "org_id", registration.orgId(), "display_name", registration.name(), "hosting_path", "personal",
                "contact_email", registration.email(), "registry_url", "https://inkbox.ai/a2a/" + registration.handle() + "/card",
                "media_type", "application/a2a-agent-card+json", "description", description), "entry creation");
        JsonNode entry;
        if (reply.status() == 409) entry = ownerEntry(registration, token);
        else {
            requireSuccess(reply, "Nanda entry creation");
            entry = reply.body();
        }
        requireOwnLocator(registration, entry);
        return entry.path("email_verified").asBoolean(false) ? Registration.Stage.RESOLVE : Registration.Stage.ENTRY_VERIFY;
    }

    private Registration.Stage verifyEntry(Registration registration) {
        JsonNode entry = ownerEntry(registration, signIn(registration));
        requireOwnLocator(registration, entry);
        if (entry.path("email_verified").asBoolean(false)) return Registration.Stage.RESOLVE;
        String verification = verificationToken(registration, "Verify your NANDA Index registration");
        if (verification == null) return Registration.Stage.ENTRY_VERIFY;
        Reply reply = nanda("POST", "/api/v1/verify-email", null, Map.of("token", verification), "registration verification");
        if (reply.status() != 409 || !"ALREADY_VERIFIED".equals(reply.body().path("error").asText())) {
            requireSuccess(reply, "Nanda registration verification");
            requireOwnLocator(registration, reply.body());
        }
        return Registration.Stage.RESOLVE;
    }

    private Registration.Stage resolve(Registration registration) {
        Reply reply = nanda("GET", "/api/v1/resolve?locator=" + encode(registration.locator()), null, null, "public resolution");
        if (reply.status() == 404) return Registration.Stage.RESOLVE;
        requireSuccess(reply, "Nanda public resolution");
        JsonNode entry = reply.body().path("entry");
        JsonNode verification = entry.path("extensions").path("org.projectnanda");
        return "exact".equals(reply.body().path("match").asText())
                && registration.locator().equals(entry.path("identifier").asText())
                && "active".equals(verification.path("status").asText())
                && verification.path("emailVerified").asBoolean(false)
                ? Registration.Stage.ACTIVE : Registration.Stage.RESOLVE;
    }

    @Override
    public void remove(Registration registration) {
        Reply reply = nanda("DELETE", "/api/v1/orgs/" + encode(registration.orgId()), signIn(registration), null, "entry deletion");
        if (reply.status() != 404) requireSuccess(reply, "Nanda entry deletion");
    }

    @Override
    public void forget(Registration registration) { passwords.delete(registration); }

    private String signIn(Registration registration) {
        Reply reply = nanda("POST", "/auth/login", null,
                Map.of("email", registration.email(), "password", passwords.passwordFor(registration)), "account sign-in");
        requireSuccess(reply, "Nanda account sign-in");
        return sessionToken(reply.body());
    }

    private JsonNode me(String token) {
        Reply reply = nanda("GET", "/api/v1/me", token, null, "account verification check");
        requireSuccess(reply, "Nanda account verification check");
        return reply.body();
    }

    private JsonNode ownerEntry(Registration registration, String token) {
        Reply reply = nanda("GET", "/api/v1/orgs/" + encode(registration.orgId()), token, null, "entry ownership check");
        requireSuccess(reply, "Nanda entry ownership check");
        return reply.body();
    }

    private void requireOwnLocator(Registration registration, JsonNode entry) {
        if (!registration.locator().equals(entry.path("identifier").asText())) {
            throw new RegistryFailure("Nanda returned a different registry identity. Check the existing entry before retrying.");
        }
    }

    private String verificationToken(Registration registration, String subject) {
        String path = "/mail/mailboxes/" + encode(registration.email()) + "/messages";
        Reply listing = request(inkbox, "GET", path + "?direction=inbound&limit=50",
                "X-API-Key", inkboxKey.get(), null, "Inkbox verification mail lookup");
        requireSuccess(listing, "Inkbox verification mail lookup");
        for (JsonNode item : listing.body().path("items")) {
            if (!expectedMail(item, subject) || item.path("id").asText().isBlank()) continue;
            Reply detail = request(inkbox, "GET", path + "/" + encode(item.path("id").asText()),
                    "X-API-Key", inkboxKey.get(), null, "Inkbox verification mail read");
            requireSuccess(detail, "Inkbox verification mail read");
            if (!expectedMail(detail.body(), subject)) continue;
            String body = detail.body().path("body_text").asText("") + "\n" + detail.body().path("body_html").asText("");
            var match = TOKEN.matcher(body);
            if (match.find()) return match.group(1);
        }
        return null;
    }

    private boolean expectedMail(JsonNode mail, String subject) {
        return SENDER.equals(mail.path("from_address").asText()) && subject.equals(mail.path("subject").asText());
    }

    private String sessionToken(JsonNode body) {
        String token = body.path("token").asText("");
        if (token.isBlank()) throw new RegistryFailure("Nanda did not return a session token. Retry registration.");
        return token;
    }

    private Reply nanda(String method, String path, String token, Object body, String operation) {
        return request(nanda, method, path, token == null ? null : "Authorization", token == null ? null : "Bearer " + token,
                body, "Nanda " + operation);
    }

    private Reply request(URI base, String method, String path, String header, String credential, Object body, String operation) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base.toString() + path))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("User-Agent", "Swarmwatch/1.0");
            if (header != null) builder.header(header, credential);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            return new Reply(response.statusCode(), parsed);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RegistryFailure(operation + " was interrupted. Retry registration.");
        } catch (Exception error) {
            throw new RegistryFailure(operation + " could not reach a valid provider response. Check connectivity and retry.");
        }
    }

    private static void requireSuccess(Reply reply, String operation) {
        if (reply.status() < 200 || reply.status() >= 300) {
            throw new RegistryFailure(operation + " failed (HTTP " + reply.status() + "). Check provider setup and retry.");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Reply(int status, JsonNode body) { }
}
