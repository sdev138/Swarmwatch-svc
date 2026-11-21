package com.athnasia.swarmwatchsvc.registry;

final class RegistryFailure extends RuntimeException {
    RegistryFailure(String message) { super(message); }
}
