package com.athnasia.swarmwatchsvc.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class NandaClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private URI base;
    private NandaClient client;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private boolean oldVerificationMail;
    private final String locator = "urn:air:host39.org:personal:sw-example@inkboxmail.com";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new NandaClient(HttpClient.newHttpClient(), json, base, base,
                () -> "fixture-inkbox-key", new PasswordStore() {
            public String passwordFor(Registration registration) { return "fixture-password"; }
            public void delete(Registration registration) { }
        });
        reply("POST /auth/register", 201, "{\"token\":\"fixture-token\"}");
        reply("POST /auth/login", 200, "{\"token\":\"fixture-token\"}");
        reply("GET /api/v1/me", 200, "{\"user_id\":\"account\",\"email_verified\":false,\"orgs\":[]}");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void registersAgentAccountWithMailboxInsteadOfInventingPhoneCredentials() {
        assertEquals(Registration.Stage.ACCOUNT_VERIFY, client.advance(registration(Registration.Stage.ACCOUNT)));
        JsonNode body = request("POST /auth/register").body();
        assertEquals("sw-example@inkboxmail.com", body.path("email").asText());
        assertEquals("fixture-password", body.path("password").asText());
        assertFalse(body.has("phone"));
    }

    @Test
    void signsInWhenTheAgentAccountAlreadyExists() {
        reply("POST /auth/register", 409, "{\"error\":\"CONFLICT\"}");
        reply("GET /api/v1/me", 200, "{\"email_verified\":true,\"orgs\":[]}");
        assertEquals(Registration.Stage.ENTRY, client.advance(registration(Registration.Stage.ACCOUNT)));
        assertEquals("fixture-password", request("POST /auth/login").body().path("password").asText());
    }

    @Test
    void verifiesOnlyExactNandaSenderAndAccountSubject() {
        mailbox("[{\"id\":\"spoof\",\"from_address\":\"attacker@example.com\",\"subject\":\"Verify your NANDA Index account email\"},"
                + "{\"id\":\"wrong-subject\",\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Do this instead\"},"
                + "{\"id\":\"real\",\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Verify your NANDA Index account email\"}]");
        reply("GET /mail/mailboxes/sw-example%40inkboxmail.com/messages/real", 200,
                "{\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Verify your NANDA Index account email\",\"body_text\":\"Confirm https://nandaindex.org/verify-account?token=abcdef0123456789\"}");
        reply("POST /auth/verify-account", 200, "{\"ok\":true}");
        assertEquals(Registration.Stage.ENTRY, client.advance(registration(Registration.Stage.ACCOUNT_VERIFY)));
        assertEquals("abcdef0123456789", request("POST /auth/verify-account").body().path("token").asText());
        assertFalse(requests.stream().anyMatch(r -> r.path().contains("/spoof") || r.path().contains("/wrong-subject")));
    }

    @Test
    void retryCanReadTheOriginalVerificationMailFromBeforeTheNewAttemptWindow() {
        oldVerificationMail = true;
        mailbox("[{\"id\":\"original\",\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Verify your NANDA Index account email\"}]");
        reply("GET /mail/mailboxes/sw-example%40inkboxmail.com/messages/original", 200,
                "{\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Verify your NANDA Index account email\",\"body_text\":\"https://nandaindex.org/verify-account?token=aabbccdd\"}");
        reply("POST /auth/verify-account", 200, "{\"ok\":true}");
        assertEquals(Registration.Stage.ENTRY, client.advance(registration(Registration.Stage.ACCOUNT_VERIFY)));
    }

    @Test
    void ignoresTokenTextFromOtherSendersAndDoesNotFollowMailboxInstructions() {
        mailbox("[{\"id\":\"spoof\",\"from_address\":\"noreply@nandaindex.org.evil.test\",\"subject\":\"Verify your NANDA Index account email\",\"snippet\":\"token=deadbeef\"}]");
        assertEquals(Registration.Stage.ACCOUNT_VERIFY, client.advance(registration(Registration.Stage.ACCOUNT_VERIFY)));
        assertFalse(requests.stream().anyMatch(r -> r.path().equals("/auth/verify-account")));
    }

    @Test
    void createsSupportedPersonalEntryUsingRealInkboxCardAndPhoneDescription() {
        reply("POST /api/v1/orgs", 201, owner(false));
        assertEquals(Registration.Stage.ENTRY_VERIFY, client.advance(registration(Registration.Stage.ENTRY)));
        JsonNode body = request("POST /api/v1/orgs").body();
        assertEquals("personal", body.path("hosting_path").asText());
        assertEquals("sw-example@inkboxmail.com", body.path("contact_email").asText());
        assertEquals("https://inkbox.ai/a2a/sw-example/card", body.path("registry_url").asText());
        assertTrue(body.path("description").asText().contains("+14155550100"));
        assertFalse(body.toString().contains("Call local businesses"));
        assertEquals("Personal agent on Inkbox. Contact by phone: +14155550100.", body.path("description").asText());
        assertFalse(body.has("contact_phone"));
        assertFalse(body.has("domain"));
    }

    @Test
    void registrationVerificationCannotClaimPublicActivation() {
        reply("GET /api/v1/orgs/inkbox-sw-example", 200, owner(false));
        mailbox("[{\"id\":\"entry\",\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Verify your NANDA Index registration\"}]");
        reply("GET /mail/mailboxes/sw-example%40inkboxmail.com/messages/entry", 200,
                "{\"from_address\":\"noreply@nandaindex.org\",\"subject\":\"Verify your NANDA Index registration\",\"body_html\":\"<a href='https://nandaindex.org/verify-email?token=0011aabb'>Verify</a>\"}");
        reply("POST /api/v1/verify-email", 200, owner(true));
        assertEquals(Registration.Stage.RESOLVE, client.advance(registration(Registration.Stage.ENTRY_VERIFY)));
        assertEquals("0011aabb", request("POST /api/v1/verify-email").body().path("token").asText());
    }

    @Test
    void onlyExactPublicResolutionWithVerifiedEmailCanBecomeActive() {
        Registration registration = registration(Registration.Stage.RESOLVE);
        reply("GET /api/v1/resolve", 200, resolution("exact", false));
        assertEquals(Registration.Stage.RESOLVE, client.advance(registration));
        reply("GET /api/v1/resolve", 200, resolution("publisher", true));
        assertEquals(Registration.Stage.RESOLVE, client.advance(registration));
        reply("GET /api/v1/resolve", 200, resolution("exact", true));
        assertEquals(Registration.Stage.ACTIVE, client.advance(registration));
        assertEquals("locator=urn%3Aair%3Ahost39.org%3Apersonal%3Asw-example%40inkboxmail.com", request("GET /api/v1/resolve").query());
    }

    @Test
    void pendingPublicEntryReturnsPendingWhileHttpErrorsNeverExposeSecrets() {
        reply("GET /api/v1/resolve", 404, "{\"error\":\"not_found\"}");
        assertEquals(Registration.Stage.RESOLVE, client.advance(registration(Registration.Stage.RESOLVE)));
        reply("POST /auth/login", 401, "{\"error\":\"UNAUTHORIZED\",\"detail\":\"fixture-password fixture-token fixture-inkbox-key\"}");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.advance(registration(Registration.Stage.ENTRY)));
        assertFalse(error.getMessage().contains("fixture-"));
        assertTrue(error.getMessage().contains("401"));
    }

    @Test
    void conflictMustBeOwnedBySameLocatorBeforeContinuing() {
        reply("POST /api/v1/orgs", 409, "{\"error\":\"CONFLICT\"}");
        reply("GET /api/v1/orgs/inkbox-sw-example", 200, "{\"identifier\":\"urn:air:host39.org:personal:other@inkboxmail.com\",\"status\":\"active\",\"email_verified\":true}");
        assertThrows(RegistryFailure.class, () -> client.advance(registration(Registration.Stage.ENTRY)));
    }

    @Test
    void removesPublicEntryBeforeDeletingItsPassword() {
        reply("DELETE /api/v1/orgs/inkbox-sw-example", 204, "");
        client.remove(registration(Registration.Stage.ACTIVE));
        assertEquals("Bearer fixture-token", request("DELETE /api/v1/orgs/inkbox-sw-example").authorization());
    }

    private Registration registration(Registration.Stage stage) {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        return new Registration("tenant", "agent", "Sam", "Example", "Call local businesses", "sw-example@inkboxmail.com", "+14155550100", stage, null, now, now, now);
    }

    private String owner(boolean verified) {
        return "{\"org_id\":\"inkbox-sw-example\",\"identifier\":\"" + locator + "\",\"status\":\"" + (verified ? "active" : "pending") + "\",\"email_verified\":" + verified + "}";
    }

    private String resolution(String match, boolean verified) {
        return "{\"match\":\"" + match + "\",\"entry\":{\"identifier\":\"" + locator + "\",\"extensions\":{\"org.projectnanda\":{\"status\":\"active\",\"emailVerified\":" + verified + "}}}}";
    }

    private void mailbox(String items) {
        reply("GET /mail/mailboxes/sw-example%40inkboxmail.com/messages", 200, "{\"items\":" + items + ",\"has_more\":false,\"next_cursor\":null}");
    }

    private void reply(String path, int status, String body) { replies.put(path, new Reply(status, body)); }
    private Request request(String path) { return requests.stream().filter(r -> (r.method() + " " + r.path()).equals(path)).findFirst().orElseThrow(); }
    private void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().equals("/auth/verify-account")) {
            reply("GET /api/v1/me", 200, "{\"email_verified\":true,\"orgs\":[]}");
        }
        byte[] raw = exchange.getRequestBody().readAllBytes();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(), raw.length == 0 ? json.createObjectNode() : json.readTree(raw), exchange.getRequestHeaders().getFirst("Authorization")));
        Reply reply = replies.getOrDefault(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath(), new Reply(500, "{\"error\":\"UNEXPECTED_FIXTURE_REQUEST\"}"));
        if (oldVerificationMail && exchange.getRequestURI().getPath().endsWith("/messages")
                && exchange.getRequestURI().getRawQuery().contains("start_datetime=")) {
            reply = new Reply(200, "{\"items\":[],\"has_more\":false}");
        }
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : response.length);
        if (reply.status() != 204) exchange.getResponseBody().write(response);
        exchange.close();
    }
    private record Reply(int status, String body) { }
    private record Request(String method, String path, String query, JsonNode body, String authorization) { }
}
