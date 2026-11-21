package com.athnasia.swarmwatchsvc.domain;

import com.athnasia.swarmwatchsvc.provider.ModelProvider;
import java.util.ArrayList;
import java.util.List;

public class AgentState {
    public String id;
    public String tenantId;
    public String name;
    public String company;
    public String goal;
    public String companyMission;
    public String voiceMission;
    public String vectorStoreId;
    public String knowledgeFileId;
    public String knowledgeContextHash;
    public String pendingKnowledgeVectorStoreId;
    public String pendingKnowledgeFileId;
    public double sentiment = 0.51;
    public String email;
    public String phone;
    public String providerIdentityId;
    public String providerHandle;
    public String status = "active";
    public String createdAt;
    public String lastCheckedAt;
    public String assessmentReason = "Awaiting the first assessment.";
    public String lastError;
    public boolean deletionRequested;
    public List<String> responses = new ArrayList<>();
    public String turnPrompt;
    public String turnChannel;
    public List<String> turnInputIds = new ArrayList<>();
    public String continuationResponseId;
    public List<ModelProvider.ToolCall> pendingToolCalls = new ArrayList<>();
    public List<ModelProvider.ToolResult> pendingToolResults = new ArrayList<>();
    public boolean turnToolFailed;
    public int toolRounds;

    public void addResponse(String response) {
        responses.add(response.length() > 4000 ? response.substring(0, 4000) : response);
        while (responses.size() > 20) responses.removeFirst();
    }
}
