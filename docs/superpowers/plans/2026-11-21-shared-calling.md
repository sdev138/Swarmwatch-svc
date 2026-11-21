# Shared Calling Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Create agents without dedicated numbers and expose a usable shared-call connection flow.

**Architecture:** Inkbox identities opt into iMessage; a provider connection record is returned by a tenant-authorized API endpoint. Shared calls resolve their caller line through the identity, while existing dedicated calls retain their number.

**Tech Stack:** Java 25, Spring Boot, RxJava, React, TypeScript, Vite.

**Spec:** `docs/superpowers/specs/2026-11-21-shared-calling-design.md`

## Global Constraints

- Preserve tenancy, missions, initial sentiment 0.51, transcript ingestion, and existing dedicated agents.
- Do not modify or publish Samarth's pending OpenAiProvider edit or installed skills.
- Use Samarth's configured Git identity and credentials; no main/master merges.
- Test locally; stop servers afterward. Deploy only a credential-scanned committed snapshot.

## Review Focus

- Free account with zero dedicated slots still creates an identity.
- Router discovery failure does not roll back a successfully created agent.
- Another tenant cannot retrieve connection instructions for an agent.
- Shared calls omit from_number; legacy dedicated calls keep their number.
- Dashboard handles loading, discovery failure, switching agents, and mobile width.

### Task 1: Shared provider and authenticated connection API

**Files:** `InkboxProvider.java`, `PhoneProvider.java`, `LocalPhoneProvider.java`, `AgentService.java`, `ApiController.java`, provider/API/service tests.

**Interfaces:** `PhoneProvider.Connection(origination, phone_number, router_number, connect_command)`; `GET /api/agents/{id}/phone-connection`.

- [x] Add failing provider tests for zero-capacity shared creation, identity voice routing, shared call body, dynamic router lookup, and preserved dedicated calling.
- [x] Implement identity creation with imessage_enabled=true and no phone_number, hosted inbound action, and shared caller origination.
- [x] Add connection lookup to service/controller with ownership checks, plus local simulated connection data.
- [x] Run provider/API/service regression tests, including missing router data and cross-tenant rejection.

### Task 2: Dashboard connection instructions

**Files:** `nanda-frontend/src/App.tsx`, `SharedPhoneSetup.tsx`, `types.ts`, `App.css`, relevant READMEs.

**Consumes:** Task 1 connection record and endpoint; no agent DTO or Datastore schema migration.

- [x] Render the shared setup card with current router number, connect command, message-compose link, retry, and honest caller connection requirements.
- [x] Update creation, call, and deletion copy; retain existing dedicated display.
- [x] Run frontend tests/build/lint; inspect shared, error, and legacy states locally at desktop/mobile widths.

### Task 3: Review and delivery

- [ ] Run full local backend tests and frontend checks; obtain a fresh review and fix material findings.
- [ ] Scan outgoing files and verify Git authorship, then commit on the child branch and merge only the original feature branch.
- [ ] Publish changed service/frontend repos through GitHub tools; leave protocol repo unchanged.
- [ ] Build the committed source with the existing Cloud Build service account/source bucket, deploy the image to Cloud Run, and verify health/revision.
- [ ] Record verification and Build link in MEMORY.md; stop local servers.
