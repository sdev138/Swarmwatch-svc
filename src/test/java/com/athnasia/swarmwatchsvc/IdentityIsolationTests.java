package com.athnasia.swarmwatchsvc;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.auth.*;
import com.athnasia.swarmwatchsvc.repository.MemorySwarmRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IdentityIsolationTests {
    @Test void aSecondGoogleSubjectCannotClaimAnotherUsersUuid() {
        var repository = new MemorySwarmRepository(new ObjectMapper());
        IdentityVerifier verifier = token -> new IdentityVerifier.Identity(token, token, token + "@example.invalid");
        var service = new AuthService(verifier, repository);
        String proposed = UUID.randomUUID().toString();
        service.session("Bearer alice", proposed);
        assertThatThrownBy(() -> service.session("Bearer bob", proposed)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(409));
        assertThat(service.session("Bearer alice", UUID.randomUUID().toString()).user_id()).isEqualTo(proposed);
    }
    @Test void authenticationRequiresAuthorizationAndAnExistingSubjectBinding() {
        var service = new AuthService(token -> new IdentityVerifier.Identity(token, "Name", "name@example.invalid"), new MemorySwarmRepository(new ObjectMapper()));
        assertThatThrownBy(() -> service.requireSession(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireSession("Bearer unbound")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.session("Bearer unbound", "not-a-uuid")).isInstanceOf(ApiException.class);
    }
    @Test void productionGoogleVerifierNeverAcceptsTheLocalDevelopmentToken() {
        var verifier = new GoogleIdentityVerifier("test-client.apps.googleusercontent.com");
        assertThatThrownBy(() -> verifier.verify("local-development-token")).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(401));
    }
}
