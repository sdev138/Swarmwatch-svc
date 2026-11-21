package com.athnasia.swarmwatchsvc.registry;

import com.athnasia.swarmwatchsvc.provider.Secrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.function.Supplier;

@Configuration
class RegistryConfiguration {
    @Bean @Profile("local")
    RegistryStore localRegistryStore() { return new MemoryRegistryStore(); }

    @Bean @Profile("!local")
    RegistryStore cloudRegistryStore(@Qualifier("datastoreSupplier") Supplier<Datastore> datastore) { return new DatastoreRegistryStore(datastore); }

    @Bean(destroyMethod = "close") @Profile("!local")
    SecretManagerPasswordStore registryPasswords(@Value("${swarmwatch.gcp-project-id}") String project) {
        return new SecretManagerPasswordStore(() -> {
            try { return SecretManagerServiceClient.create(); }
            catch (IOException failure) { throw new RegistryFailure("Secret Manager is unavailable. Check credentials and retry registration."); }
        }, project);
    }

    @Bean @Profile("local")
    Registrar localRegistrar() {
        return new Registrar() {
            public Registration.Stage advance(Registration registration) { throw new IllegalStateException("Local registry never accesses providers."); }
            public void remove(Registration registration) { throw new IllegalStateException("Local registry never accesses providers."); }
            public void forget(Registration registration) { throw new IllegalStateException("Local registry never accesses providers."); }
        };
    }

    @Bean @Profile("!local")
    Registrar productionRegistrar(HttpClient http, ObjectMapper json, Secrets secrets, SecretManagerPasswordStore passwords) {
        return new NandaClient(http, json, URI.create("https://api.nandaindex.org"), URI.create("https://inkbox.ai/api/v1"), secrets::inkboxKey, passwords);
    }

    @Bean
    RegistryCoordinator registryCoordinator(RegistryStore store, Registrar registrar, Environment environment) {
        return new RegistryCoordinator(store, registrar, Clock.systemUTC(), environment.acceptsProfiles(Profiles.of("local")));
    }
}
