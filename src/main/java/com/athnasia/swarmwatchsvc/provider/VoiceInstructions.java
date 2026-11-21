package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.api.ApiException;

public final class VoiceInstructions {
    private VoiceInstructions() {}
    public static String render(String name, String company, String goal, String mission) {
        String instructions = "You are " + name + " working for " + company + ". Shared company mission: " + mission
                + ". Your individual goal: " + goal + ". Be concise; report observable results and blockers. Do not imply you run GPT-6-Luna.";
        if (instructions.length() > 8000) throw new ApiException(400, "Shared mission, name, company, and goal must together fit Inkbox's 8000-character voice instruction limit. Shorten the mission or individual goal.");
        return instructions;
    }
}
