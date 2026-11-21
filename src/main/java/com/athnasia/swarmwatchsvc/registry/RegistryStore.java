package com.athnasia.swarmwatchsvc.registry;

import java.util.List;
import java.util.Optional;

interface RegistryStore {
    Optional<Registration> find(String tenant, String agent);
    List<Registration> pending();
    void save(Registration registration);
    void delete(String tenant, String agent);
}
