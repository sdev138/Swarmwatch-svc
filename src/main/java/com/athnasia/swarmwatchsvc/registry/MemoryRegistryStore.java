package com.athnasia.swarmwatchsvc.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class MemoryRegistryStore implements RegistryStore {
    private final Map<String, Registration> records = new ConcurrentHashMap<>();
    private static String key(String tenant, String agent) { return tenant + "/" + agent; }
    public Optional<Registration> find(String tenant, String agent) { return Optional.ofNullable(records.get(key(tenant, agent))); }
    public List<Registration> pending() { return records.values().stream().filter(Registration::pending).toList(); }
    public void save(Registration record) { records.put(key(record.tenantId(), record.agentId()), record); }
    public void delete(String tenant, String agent) { records.remove(key(tenant, agent)); }
}
