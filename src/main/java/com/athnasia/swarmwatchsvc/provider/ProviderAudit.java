package com.athnasia.swarmwatchsvc.provider;

import java.util.*;

public final class ProviderAudit {
    private ProviderAudit() {}
    public static Map<String, Object> sanitize(Map<String, Object> item) {
        var result = new LinkedHashMap<String, Object>();
        item.forEach((key, value) -> { if (!key.equals("encrypted_content")) result.put(key, value(value)); });
        return result;
    }
    private static Object value(Object value) {
        if (value instanceof String text) {
            if (text.startsWith("data:image/")) return "[screenshot omitted from persistent audit]";
            return text.length() > 20000 ? text.substring(0, 20000) + " [truncated in audit]" : text;
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> { if (!key.toString().equals("encrypted_content")) result.put(key.toString(), value(nested)); });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().limit(100).map(ProviderAudit::value).toList();
        return value;
    }
}
