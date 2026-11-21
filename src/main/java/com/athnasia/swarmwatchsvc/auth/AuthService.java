package com.athnasia.swarmwatchsvc.auth;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.domain.UserSession;
import com.athnasia.swarmwatchsvc.repository.SwarmRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final IdentityVerifier verifier;
    private final SwarmRepository repository;
    public AuthService(IdentityVerifier verifier, SwarmRepository repository) { this.verifier = verifier; this.repository = repository; }
    private IdentityVerifier.Identity identity(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new ApiException(401, "Sign in with Google to continue.");
        return verifier.verify(authorization.substring(7));
    }
    public UserSession session(String authorization, String proposedId) {
        var identity = identity(authorization);
        var existing = repository.user(identity.subject());
        if (existing.isPresent()) return existing.get();
        try {
            if (!UUID.fromString(proposedId).toString().equalsIgnoreCase(proposedId)) throw new IllegalArgumentException();
        } catch (RuntimeException error) { throw new ApiException(400, "First signup requires a valid user_id UUID."); }
        return repository.bind(identity.subject(), new UserSession(UUID.fromString(proposedId).toString(), identity.name(), identity.email()));
    }
    public UserSession requireSession(String authorization) {
        var identity = identity(authorization);
        return repository.user(identity.subject()).orElseThrow(() -> new ApiException(401, "Complete sign-in through /api/session first."));
    }
}
