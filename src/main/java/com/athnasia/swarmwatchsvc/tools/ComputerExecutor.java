package com.athnasia.swarmwatchsvc.tools;

import java.util.Map;

public interface ComputerExecutor extends AutoCloseable {
    record Result(String screenshotDataUrl, String currentUrl) { }
    Result execute(String tenantId, String agentId, Map<String, Object> computerCall);
    void release(String tenantId, String agentId);
    @Override void close();
}
