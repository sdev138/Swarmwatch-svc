package com.athnasia.swarmwatchsvc.registry;

import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.ApiExceptionFactory;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistryPersistenceTest {
    @Test
    void datastoreRoundTripPreservesRetryDeadlineStageAndPhoneWithoutPersistingSecrets() {
        Datastore datastore = mock(Datastore.class);
        when(datastore.newKeyFactory()).thenAnswer(ignored -> new KeyFactory("fixture-project").setKind("NandaRegistration"));
        AtomicReference<Entity> saved = new AtomicReference<>();
        when(datastore.put(any(Entity.class))).thenAnswer(call -> { saved.set(call.getArgument(0)); return call.getArgument(0); });
        when(datastore.get(any(Key.class))).thenAnswer(ignored -> saved.get());
        RegistryStore store = new DatastoreRegistryStore(() -> datastore);
        Registration record = registration();
        store.save(record);
        Registration restored = store.find("tenant", "agent").orElseThrow();
        assertEquals(record, restored);
        assertEquals("+14155550100", restored.view().phone_number());
        assertTrue(saved.get().getBoolean("pending"));
        assertFalse(saved.get().contains("password"));
        assertFalse(saved.get().contains("token"));
    }

    @Test
    void passwordIsCreatedOnlyInSecretManagerAndReusedAcrossRegistrationRetries() {
        SecretManagerServiceClient secrets = mock(SecretManagerServiceClient.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(secrets.accessSecretVersion(anyString())).thenAnswer(ignored -> {
            if (stored.get() == null) throw missing();
            return AccessSecretVersionResponse.newBuilder().setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(stored.get()))).build();
        });
        when(secrets.addSecretVersion(any(AddSecretVersionRequest.class))).thenAnswer(call -> {
            AddSecretVersionRequest request = call.getArgument(0);
            stored.set(request.getPayload().getData().toStringUtf8());
            return SecretVersion.newBuilder().setName(request.getParent() + "/versions/1").build();
        });
        try (var passwords = new SecretManagerPasswordStore(() -> secrets, "fixture-project")) {
            String first = passwords.passwordFor(registration());
            String retried = passwords.passwordFor(registration());
            assertEquals(24, first.length());
            assertEquals(first, retried);
            assertEquals(stored.get(), first);
            var captured = ArgumentCaptor.forClass(AddSecretVersionRequest.class);
            verify(secrets, times(1)).addSecretVersion(captured.capture());
            assertTrue(captured.getValue().getParent().startsWith("projects/fixture-project/secrets/sw-nanda-"));
            assertFalse(captured.getValue().getParent().contains("tenant"));
        }
    }

    private static NotFoundException missing() {
        return (NotFoundException) ApiExceptionFactory.createException(new IllegalStateException("not found"), GrpcStatusCode.of(Status.Code.NOT_FOUND), false);
    }

    private static Registration registration() {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        return new Registration("tenant", "agent", "Sam", "Example", "Goal", "sw-agent@inkboxmail.com", "+14155550100", Registration.Stage.RESOLVE, null, now, now.plusSeconds(30), now.plusSeconds(60));
    }
}
