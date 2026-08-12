# Tasks: Agent 会话发送快捷键可配置

**Input**: Design documents from `/specs/012-agent-send-shortcut/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [x] T001 Confirm feature scope: frontend-only in `oryxos-web/src/main/frontend/src/App.vue` (no Java/API changes)

## Phase 2: Foundational

- [x] T002 Add `CHAT_SEND_MODE_KEY`, mode load/save helpers, and `chatSendMode` ref per [data-model.md](./data-model.md)

## Phase 3: User Story 1 & 2 — 快捷键发送 + 模式切换 (P1)

**Goal**: Keyboard send matches button; two modes with inline toggle and hint.

**Independent Test**: [quickstart.md](./quickstart.md) 场景 A/B

- [x] T003 [US1] Implement `onChatInputKeydown` per [contracts/chat-input-ui.md](./contracts/chat-input-ui.md)
- [x] T004 [US1] Guard `sendChat()` with `chat.sending` (parity with disabled button)
- [x] T005 [US2] Wire textarea `@keydown` and mode toggle UI (md-seg) + `chatSendHint` computed

## Phase 4: User Story 3 — 持久化 (P2)

**Goal**: Refresh restores last mode globally.

**Independent Test**: [quickstart.md](./quickstart.md) 场景 B step 2、场景 D

- [x] T006 [US3] Persist mode on toggle via `localStorage.setItem`; hydrate on load with default `modifier`

## Phase 5: Polish

- [x] T007 Add `.chat-send-bar` styles aligned with existing admin tokens
- [x] T008 Run `npm run build` in `oryxos-web/src/main/frontend` and manual quickstart walkthrough

## Dependencies

```text
T001 → T002 → T003–T006 (parallel UI) → T007 → T008
```

## Implementation Strategy

MVP = T002–T006 (single file). T008 = verification gate before merge.
