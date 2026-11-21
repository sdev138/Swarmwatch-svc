package com.athnasia.swarmwatchsvc.registry;

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.protobuf.ByteString;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Supplier;

final class SecretManagerPasswordStore implements PasswordStore, AutoCloseable {
    private final Supplier<SecretManagerServiceClient> factory;
    private final String project;
    private volatile SecretManagerServiceClient client;

    SecretManagerPasswordStore(Supplier<SecretManagerServiceClient> factory, String project) {
        this.factory = factory;
        this.project = project;
    }

    private synchronized SecretManagerServiceClient client() {
        if (client == null) client = factory.get();
        return client;
    }

    @Override
    public String passwordFor(Registration registration) {
        String secret = secret(registration);
        try { return read(secret); }
        catch (NotFoundException missing) {
            try {
                client().createSecret(CreateSecretRequest.newBuilder().setParent("projects/" + project)
                        .setSecretId(secret.substring(secret.lastIndexOf('/') + 1))
                        .setSecret(Secret.newBuilder().setReplication(Replication.newBuilder().setAutomatic(Replication.Automatic.newBuilder())))
                        .build());
            } catch (AlreadyExistsException exists) {
                // A prior process can create the secret before adding its first version.
            }
            try { return read(secret); }
            catch (NotFoundException noVersion) {
                String password = randomPassword();
                client().addSecretVersion(AddSecretVersionRequest.newBuilder().setParent(secret)
                        .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(password))).build());
                return password;
            }
        }
    }

    private String read(String secret) {
        String password = client().accessSecretVersion(secret + "/versions/latest").getPayload().getData().toStringUtf8();
        if (password.isBlank()) throw new RegistryFailure("The agent's Nanda credential is empty. Repair its Secret Manager secret before retrying.");
        return password;
    }

    @Override
    public void delete(Registration registration) {
        try { client().deleteSecret(secret(registration)); }
        catch (NotFoundException missing) { }
    }

    private String secret(Registration registration) {
        try {
            String identity = registration.tenantId() + "/" + registration.agentId();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            return "projects/" + project + "/secrets/sw-nanda-" + digest;
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(24);
        for (int index = 0; index < 24; index++) password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return password.toString();
    }

    @Override
    public void close() { if (client != null) client.close(); }
}
