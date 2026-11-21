package com.athnasia.swarmwatchsvc.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConversationEntry(String id, String role, String content, String channel,
        String created_at, Double sentiment, String call_id, Long seq,
        Map<String, Object> provenance, Map<String, Object> protocol) {
    public static ConversationEntry message(String role, String content, String channel) {
        return new ConversationEntry(UUID.randomUUID().toString(), role, content, channel,
                Instant.now().toString(), null, null, null, Map.of(), Map.of());
    }
}
