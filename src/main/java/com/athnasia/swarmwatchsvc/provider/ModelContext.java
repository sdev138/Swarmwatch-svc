package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.domain.ConversationEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

final class ModelContext {
    private ModelContext() {}
    static List<ConversationEntry> recent(List<ConversationEntry> history, ObjectMapper json) {
        int remaining = 100000;
        var selected = new ArrayList<ConversationEntry>();
        for (int i = history.size() - 1; i >= 0; i--) {
            var entry = history.get(i);
            int size;
            try { size = json.writeValueAsString(entry).length(); }
            catch (Exception failure) { throw new ProviderException("Conversation context could not be encoded."); }
            if (size > remaining) {
                if (selected.isEmpty()) {
                    String text = entry.content();
                    if (text.length() > 16000) text = "[Earlier part omitted from model context] " + text.substring(text.length() - 16000);
                    selected.add(new ConversationEntry(entry.id(), entry.role(), text, entry.channel(), entry.created_at(), entry.sentiment(),
                            entry.call_id(), entry.seq(), Map.of("context_truncated", true), Map.of()));
                }
                break;
            }
            selected.add(entry); remaining -= size;
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }
}
