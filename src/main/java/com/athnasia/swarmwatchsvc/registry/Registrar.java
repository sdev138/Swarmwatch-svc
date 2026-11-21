package com.athnasia.swarmwatchsvc.registry;

interface Registrar {
    Registration.Stage advance(Registration registration);
    void remove(Registration registration);
    void forget(Registration registration);
}
