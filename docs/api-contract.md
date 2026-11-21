# Swarmwatch API contract

All API responses use JSON. Errors are `{"message":"Actionable explanation"}`. Production requests require `Authorization: Bearer <Google ID token>`. Explicit local profile accepts `Bearer local-development-token` only and binds a fixed local Google subject. No tenant ID from request query/header authorizes access.

- `GET /api/config` (public): `{"local_mode":boolean,"google_client_id":string,"agent_model":string,"decision_model":string,"check_interval_seconds":number}`.
- `POST /api/session`, body `{"user_id":"frontend UUID for first signup"}`: `{"user_id":string,"name":string,"email":string}`. Bind verified subject to user once; return existing UUID on later login.
- `GET /api/mission`: `{"company_mission":string}`; authenticated tenant shared mission.
- `PUT /api/mission`, body `{"company_mission":string}`: saves 1–8000 characters. Every agent uses this in its system instructions; creation requires a saved mission.
- `GET /api/agents`: `Agent[]` for signed-in user.
- `POST /api/agents`, body `{"agent_Name":string,"company":string,"goal":string}`: created `Agent` (201), conflict 409 for fourth agent. Frontend creates 1–3 sequentially and handles partial failure by refreshing persisted agents.
- `GET /api/agents/{id}`: `Agent`.
- `GET /api/agents/{id}/phone-connection`: `{origination,phone_number,router_number,connect_command}`; ownership required. Shared identities return `origination=shared_imessage_number`, an empty `phone_number`, and current identity-specific router instructions. Legacy dedicated identities return `dedicated_number` with their number; local fixtures return `local` without usable contact details. Router discovery does not create or modify resources.
- `DELETE /api/agents/{id}`: 204 on provider + persistence cleanup.
- `GET /api/agents/{id}/conversation`: `{"agent_conversation_json":"JSON array encoded as string"}`.
- `POST /api/agents/{id}/messages`, body `{"message":string}`: 202 with queued `ConversationEntry`.
- `POST /api/agents/{id}/check-in`: 202 `{"message":string}`; schedules a check through the same serialized runtime.
- `POST /api/agents/{id}/calls`, body `{"to_number":"E.164","reason":string}`: 202 `{"id":string,"status":string}`.
- `POST /api/agents/{id}/collaborate`, body `{"target_agent_id":string,"message":string}`: 202 `{"message":string}`. Both agents must belong to same authenticated tenant; use PAP 1.0 message author envelope in persisted entries.

`Agent`: `{"agent_Id":string,"user_Id":string,"agent_Name":string,"agent_Sentiment":number,"inkbox_email":string,"phone_number":string,"agent_responses":string[],"company":string,"goal":string,"status":"active|working|error|deleting","created_at":ISO8601,"last_checked_at":ISO8601|null,"assessment_reason":string,"last_error":string|null}`. New shared identities have an empty `phone_number`; this is expected and does not indicate provisioning is pending. Additional provider IDs can be internal and need not be returned.

`ConversationEntry`: `{"id":string,"role":"user|assistant|system|assessment","content":string,"channel":"dashboard|check_in|phone|protocol","created_at":ISO8601,"sentiment":number|null}`. Additional fields may include call_id, seq, processed, protocol envelope. Frontend ignores unknown keys. Retain raw transcript provenance.

Frontend polls every 4 seconds, never clears history during same-agent refresh, cancels stale selection requests and does not accept credentials from URL. Local profile returns conspicuously simulated responses and phone placeholders that cannot initiate real calls.

Registry: `GET /api/agents/{id}/registry` returns `{status,identifier,error,phone_number,updated_at}`. `POST /api/agents/{id}/registry/retry` returns 202. Supported Nanda personal locators use the verified internal Inkbox mailbox; calling instructions stay primary in UI. Shared outbound calls require the destination to have an active iMessage connection to the identity; the provider enforces that restriction and usage limits.
