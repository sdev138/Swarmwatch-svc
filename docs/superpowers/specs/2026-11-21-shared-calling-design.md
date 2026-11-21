# Shared iMessage calling

Samarth approved replacing dedicated-number creation with Inkbox's Free-tier shared iMessage calling. New agents enable iMessage without claiming a dedicated number. Existing dedicated agents retain their current numbers and calling path.

The service configures hosted voice on the identity and leaves `phone_number` empty for new shared identities. A tenant-authorized connection endpoint retrieves the current router number and identity-specific connect command from Inkbox at request time. The dashboard explains how the human connects first, then calls the assigned contact. It never labels the router as the agent's dedicated number.

Shared outbound calls select `shared_imessage_number`, include the identity ID, and omit `from_number`. Inkbox enforces active recipient connections and account usage limits. Existing transcript ingestion already uses identity-scoped call history and stays intact. Phone-based agent-to-agent dialing cannot assume that a shared identity owns a stable public number; existing protocol collaboration remains available.

Keep the existing dashboard design and tenant authorization. No billing upgrades, automatic messages, arbitrary account migration, or unrelated provider changes. Preserve Samarth's pending OpenAiProvider logging edit and newly installed skills. Test locally, scan published files for credentials, publish the changed service/frontend repos using Samarth's Git identity and GitHub authentication, and deploy the committed snapshot to the existing Cloud Run service.

Approach: use shared calling for all new agents, with compatibility for existing dedicated agents. Automatic paid-number allocation and an additional mode selector would conflict with the selected Free-tier flow.
