package com.athnasia.swarmwatchsvc.auth;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.util.List;

public class GoogleIdentityVerifier implements IdentityVerifier {
    private final GoogleIdTokenVerifier verifier;
    public GoogleIdentityVerifier(String audience) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Google OAuth client ID is required.");
        verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(List.of(audience)).build();
    }
    public Identity verify(String token) {
        try {
            var verified = verifier.verify(token);
            if (verified == null || !Boolean.TRUE.equals(verified.getPayload().getEmailVerified())) throw new IllegalArgumentException();
            var claims = verified.getPayload();
            String name = claims.get("name") instanceof String value ? value : claims.getEmail();
            return new Identity(claims.getSubject(), name, claims.getEmail());
        } catch (Exception failure) { throw new ApiException(401, "Google sign-in token is invalid or expired. Sign in again."); }
    }
}
