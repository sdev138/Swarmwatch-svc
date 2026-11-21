package com.athnasia.swarmwatchsvc.auth;

public interface IdentityVerifier {
    record Identity(String subject, String name, String email) {}
    Identity verify(String token);
}
