package com.athnasia.swarmwatchsvc.registry;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.StringValue;
import com.google.cloud.datastore.StructuredQuery.PropertyFilter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

final class DatastoreRegistryStore implements RegistryStore {
    private static final String KIND = "NandaRegistration";
    private final Supplier<Datastore> datastore;

    DatastoreRegistryStore(Supplier<Datastore> datastore) { this.datastore = datastore; }

    private Key key(String tenant, String agent) { return datastore.get().newKeyFactory().setKind(KIND).newKey(tenant + "/" + agent); }

    public Optional<Registration> find(String tenant, String agent) {
        return Optional.ofNullable(datastore.get().get(key(tenant, agent))).map(this::read);
    }

    public List<Registration> pending() {
        var query = Query.newEntityQueryBuilder().setKind(KIND).setFilter(PropertyFilter.eq("pending", true)).build();
        var results = datastore.get().run(query);
        List<Registration> registrations = new ArrayList<>();
        while (results.hasNext()) registrations.add(read(results.next()));
        return registrations;
    }

    public void save(Registration record) {
        Entity entity = Entity.newBuilder(key(record.tenantId(), record.agentId()))
                .set("tenantId", text(record.tenantId())).set("agentId", text(record.agentId()))
                .set("name", text(record.name())).set("company", text(record.company())).set("goal", text(record.goal()))
                .set("email", text(record.email())).set("phone", text(record.phone())).set("stage", record.stage().name())
                .set("error", text(record.error() == null ? "" : record.error()))
                .set("startedAt", record.startedAt().toString()).set("updatedAt", record.updatedAt().toString())
                .set("nextAttemptAt", record.nextAttemptAt().toString()).set("pending", record.pending()).build();
        datastore.get().put(entity);
    }

    public void delete(String tenant, String agent) { datastore.get().delete(key(tenant, agent)); }

    private Registration read(Entity entity) {
        String error = entity.getString("error");
        return new Registration(entity.getString("tenantId"), entity.getString("agentId"), entity.getString("name"),
                entity.getString("company"), entity.getString("goal"), entity.getString("email"), entity.getString("phone"),
                Registration.Stage.valueOf(entity.getString("stage")), error.isEmpty() ? null : error,
                Instant.parse(entity.getString("startedAt")), Instant.parse(entity.getString("updatedAt")), Instant.parse(entity.getString("nextAttemptAt")));
    }

    private static StringValue text(String value) { return StringValue.newBuilder(value).setExcludeFromIndexes(true).build(); }
}
