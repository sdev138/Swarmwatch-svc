package com.athnasia.swarmwatchsvc.registry;

interface PasswordStore {
    String passwordFor(Registration registration);
    void delete(Registration registration);
}
