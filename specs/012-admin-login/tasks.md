# Tasks: 管理后台用户登录

**Input**: Design documents from `/specs/012-admin-login/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/authentication-api.md`, `quickstart.md`

**Tests**: Included because the implementation plan explicitly calls for MockMvc, DataJpaTest, SpringBootTest/TestRestTemplate, and frontend build verification.

**Organization**: Tasks are grouped by user story so each story can be implemented and verified independently after the shared foundation is complete.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel because it touches different files and does not depend on incomplete same-phase work
- **[Story]**: User story label from `spec.md`
- Every task includes an exact target file path

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add required dependencies and configuration surfaces before security code is introduced.

- [X] T001 Add `spring-boot-starter-security` dependency in `oryxos-web/pom.xml`
- [X] T002 Add admin authentication configuration placeholders for username, encoded password hash, session timeout, and secure cookie in `oryxos-boot/src/main/resources/application.yml`
- [X] T003 Add user-facing admin authentication environment variable examples in `config/application.yml.example`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared contracts, persistence, configuration, and security response infrastructure that all user stories depend on.

**CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 [P] Create `AdminAuthEventType` enum in `oryxos-core/src/main/java/io/oryxos/core/auth/AdminAuthEventType.java`
- [X] T005 [P] Create immutable `AdminAuthEvent` value object in `oryxos-core/src/main/java/io/oryxos/core/auth/AdminAuthEvent.java`
- [X] T006 Create `AdminAuthAuditStore` interface in `oryxos-core/src/main/java/io/oryxos/core/auth/AdminAuthAuditStore.java`
- [X] T007 Add `admin_auth_events` table and indexes to `oryxos-storage/src/main/resources/schema.sql`
- [X] T008 [P] Create `AdminAuthEventEntity` JPA entity in `oryxos-storage/src/main/java/io/oryxos/storage/AdminAuthEventEntity.java`
- [X] T009 Create `AdminAuthEventRepository` in `oryxos-storage/src/main/java/io/oryxos/storage/AdminAuthEventRepository.java`
- [X] T010 Implement `JpaAdminAuthAuditStore` in `oryxos-storage/src/main/java/io/oryxos/storage/JpaAdminAuthAuditStore.java`
- [X] T011 [P] Create `AdminSecurityProperties` in `oryxos-web/src/main/java/io/oryxos/web/config/AdminSecurityProperties.java`
- [X] T012 [P] Create shared auth exception types in `oryxos-web/src/main/java/io/oryxos/web/auth/AdminAuthException.java`
- [X] T013 Create security JSON response helper in `oryxos-web/src/main/java/io/oryxos/web/config/SecurityApiResponseWriter.java`
- [X] T014 Register `AdminSecurityProperties` and `AdminAuthAuditStore` beans in `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`
- [X] T015 Extend `GlobalExceptionHandler` for 401, 403, 429, and auth-specific 503 responses in `oryxos-web/src/main/java/io/oryxos/web/GlobalExceptionHandler.java`

**Checkpoint**: Core auth contracts, storage schema, runtime wiring, and JSON error infrastructure exist.

---

## Phase 3: User Story 1 - 管理员安全进入管理后台 (Priority: P1) MVP

**Goal**: A configured administrator can log in with correct credentials, access protected management APIs and the admin UI, then log out; unauthenticated access is blocked without leaking management data.

**Independent Test**: Configure one initial administrator, get CSRF, log in successfully, access `/api/v1/info` and `/api/v1/agents`, log out, then verify the same protected API returns 401.

### Tests for User Story 1

- [X] T016 [P] [US1] Add MockMvc security tests for CSRF, successful login, protected API access, and logout in `oryxos-web/src/test/java/io/oryxos/web/auth/AdminAuthSecurityTest.java`
- [X] T017 [P] [US1] Add SpringBootTest login flow coverage with cookies and protected API access in `oryxos-boot/src/test/java/io/oryxos/boot/AdminLoginFlowIT.java`

### Implementation for User Story 1

- [X] T018 [P] [US1] Create login request DTO in `oryxos-web/src/main/java/io/oryxos/web/controller/dto/LoginRequest.java`
- [X] T019 [P] [US1] Create auth status response DTO in `oryxos-web/src/main/java/io/oryxos/web/controller/dto/AuthStatusView.java`
- [X] T020 [P] [US1] Create CSRF response DTO in `oryxos-web/src/main/java/io/oryxos/web/controller/dto/CsrfTokenView.java`
- [X] T021 [P] [US1] Create login response DTO in `oryxos-web/src/main/java/io/oryxos/web/controller/dto/LoginView.java`
- [X] T022 [P] [US1] Implement local credential validation service in `oryxos-web/src/main/java/io/oryxos/web/auth/LocalAdminIdentityService.java`
- [X] T023 [US1] Implement security filter chain with public auth bootstrap endpoints, public `/admin/**`, public health, protected management APIs, session timeout, CSRF, and JSON 401/403 handlers in `oryxos-web/src/main/java/io/oryxos/web/config/AdminSecurityConfig.java`
- [X] T024 [US1] Implement status, csrf, login, and logout endpoints in `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java`
- [X] T025 [US1] Persist login-success and logout events before finalizing session changes in `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java`
- [X] T026 [P] [US1] Add shared same-origin fetch, ApiResponse parsing, CSRF header handling, and session-expiry callbacks in `oryxos-web/src/main/frontend/src/api.js`
- [X] T027 [US1] Refactor existing admin SPA data loading to use `api.js` instead of raw `fetch` in `oryxos-web/src/main/frontend/src/App.vue`
- [X] T028 [US1] Add top-level authenticated/unauthenticated rendering so protected management data loads only after auth status succeeds in `oryxos-web/src/main/frontend/src/App.vue`
- [X] T029 [US1] Implement the OryxOS-style login page, loading state, unified error state, session-expired state, and logout control using existing visual tokens in `oryxos-web/src/main/frontend/src/App.vue`
- [X] T030 [US1] Add responsive login layout styles using only existing token values in `oryxos-web/src/main/frontend/src/styles/tokens.css`

**Checkpoint**: User Story 1 is functional and independently testable as the MVP.

---

## Phase 4: User Story 2 - 系统安全处理登录失败 (Priority: P1)

**Goal**: Invalid credentials receive the same generic response, repeated failures for the same submitted account trigger a temporary lockout, and no failed attempt creates an authenticated session.

**Independent Test**: Submit unknown username and wrong password attempts, verify identical 401 responses, then submit five failures within 15 minutes and verify subsequent attempts return 429 until the window expires.

### Tests for User Story 2

- [X] T031 [P] [US2] Add unit tests for failure window, lockout, reset after success, and post-window retry in `oryxos-web/src/test/java/io/oryxos/web/auth/LoginFailureTrackerTest.java`
- [X] T032 [P] [US2] Add MockMvc tests for generic 401 responses and 429 lockout behavior in `oryxos-web/src/test/java/io/oryxos/web/auth/AdminAuthFailureTest.java`

### Implementation for User Story 2

- [X] T033 [P] [US2] Implement per-username in-process failure tracking with injectable `Clock` in `oryxos-web/src/main/java/io/oryxos/web/auth/LoginFailureTracker.java`
- [X] T034 [US2] Integrate failure tracking, generic invalid-credential responses, and lockout responses into `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java`
- [X] T035 [US2] Persist login-failed and login-locked audit events without passwords, hashes, CSRF tokens, or cookies in `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java`
- [X] T036 [US2] Render generic invalid-credential and temporary-lockout feedback without account-existence hints in `oryxos-web/src/main/frontend/src/App.vue`

**Checkpoint**: User Stories 1 and 2 both work independently and preserve the same login UX.

---

## Phase 5: User Story 3 - 运维初始化并追溯访问 (Priority: P2)

**Goal**: Unconfigured deployments never open management features with defaults, configured deployments expose sanitized auth audit events to authenticated administrators, and future identity-provider replacement remains behind the same contract.

**Independent Test**: Start without admin configuration, verify status shows `configured=false` and protected APIs remain denied; configure admin, perform success/failure/logout, then query recent auth events and verify sanitized newest-first records.

### Tests for User Story 3

- [X] T037 [P] [US3] Add DataJpaTest coverage for auth event persistence and newest-first bounded query in `oryxos-storage/src/test/java/io/oryxos/storage/AdminAuthEventRepositoryTest.java`
- [X] T038 [P] [US3] Add MockMvc tests for unconfigured deployment status, denied protected access, and auth event query limits in `oryxos-web/src/test/java/io/oryxos/web/auth/AdminAuthOperationsTest.java`
- [X] T039 [P] [US3] Add SpringBootTest coverage for unconfigured deployment safety in `oryxos-boot/src/test/java/io/oryxos/boot/AdminAuthUnconfiguredIT.java`

### Implementation for User Story 3

- [X] T040 [P] [US3] Create auth event response DTO in `oryxos-web/src/main/java/io/oryxos/web/controller/dto/AdminAuthEventView.java`
- [X] T041 [US3] Implement bounded newest-first auth event query in `oryxos-storage/src/main/java/io/oryxos/storage/JpaAdminAuthAuditStore.java`
- [X] T042 [US3] Add authenticated `GET /api/v1/auth/events?limit={1..100}` handling and limit validation in `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java`
- [X] T043 [US3] Ensure invalid or missing admin configuration reports `configured=false` without fallback credentials in `oryxos-web/src/main/java/io/oryxos/web/config/AdminSecurityProperties.java`
- [X] T044 [US3] Render the unconfigured-admin state with actionable non-sensitive deployment guidance in `oryxos-web/src/main/frontend/src/App.vue`
- [X] T045 [US3] Keep the local identity adapter isolated behind `LocalAdminIdentityService` so future OIDC/SAML providers can preserve the same session and audit contract in `oryxos-web/src/main/java/io/oryxos/web/auth/LocalAdminIdentityService.java`

**Checkpoint**: All user stories are independently functional and auditable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation, documentation tightening, and security regression checks across all stories.

- [X] T046 [P] Update implementation notes and environment guidance in `specs/012-admin-login/quickstart.md`
- [X] T047 [P] Verify no sensitive auth material is logged or persisted by reviewing `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java`
- [X] T048 [P] Verify login page visual consistency, desktop layout, narrow-screen layout, no text overflow, and no protected-data preloading in `oryxos-web/src/main/frontend/src/App.vue`
- [X] T049 Run storage tests from `specs/012-admin-login/quickstart.md`
- [X] T050 Run web tests from `specs/012-admin-login/quickstart.md`
- [X] T051 Run frontend build from `specs/012-admin-login/quickstart.md`
- [X] T052 Run full Maven verification from `specs/012-admin-login/quickstart.md` — passed under JDK 21 with Git Bash on PATH using `.tools/apache-maven-3.9.9/bin/mvn.cmd verify`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; start immediately.
- **Foundational (Phase 2)**: Depends on Setup completion; blocks all user stories.
- **User Stories (Phase 3+)**: Depend on Foundational completion.
- **Polish (Phase 6)**: Depends on all selected user stories being complete.

### User Story Dependencies

- **User Story 1 (P1)**: Starts after Foundational; this is the MVP and provides normal login, logout, session, CSRF, and protected access.
- **User Story 2 (P1)**: Starts after Foundational; integrates with the same login endpoint and can be implemented after or alongside US1 once `AuthApiController` ownership is coordinated.
- **User Story 3 (P2)**: Starts after Foundational; audit query depends on shared audit persistence and benefits from US1/US2 events, but unconfigured safety can be tested independently.

### Within Each User Story

- Write story tests first and confirm they fail before implementation.
- DTOs and small pure services can be created before controller integration.
- Security filter chain and controller integration must be completed before frontend authenticated loading can pass.
- Story checkpoint must pass before moving to the next priority story in a single-developer flow.

### Parallel Opportunities

- T004, T005, T008, T011, and T012 can run in parallel after Setup.
- T016 and T017 can run in parallel for US1 test creation.
- T018 through T022 can run in parallel before US1 controller/security integration.
- T031 and T032 can run in parallel for US2 test creation.
- T037, T038, and T039 can run in parallel for US3 test creation.
- T046, T047, and T048 can run in parallel during Polish.

---

## Parallel Example: User Story 1

```text
Task: "Add MockMvc security tests for CSRF, successful login, protected API access, and logout in oryxos-web/src/test/java/io/oryxos/web/auth/AdminAuthSecurityTest.java"
Task: "Add SpringBootTest login flow coverage with cookies and protected API access in oryxos-boot/src/test/java/io/oryxos/boot/AdminLoginFlowIT.java"
Task: "Create login request DTO in oryxos-web/src/main/java/io/oryxos/web/controller/dto/LoginRequest.java"
Task: "Create auth status response DTO in oryxos-web/src/main/java/io/oryxos/web/controller/dto/AuthStatusView.java"
Task: "Create CSRF response DTO in oryxos-web/src/main/java/io/oryxos/web/controller/dto/CsrfTokenView.java"
Task: "Create login response DTO in oryxos-web/src/main/java/io/oryxos/web/controller/dto/LoginView.java"
Task: "Implement local credential validation service in oryxos-web/src/main/java/io/oryxos/web/auth/LocalAdminIdentityService.java"
```

## Parallel Example: User Story 2

```text
Task: "Add unit tests for failure window, lockout, reset after success, and post-window retry in oryxos-web/src/test/java/io/oryxos/web/auth/LoginFailureTrackerTest.java"
Task: "Add MockMvc tests for generic 401 responses and 429 lockout behavior in oryxos-web/src/test/java/io/oryxos/web/auth/AdminAuthFailureTest.java"
Task: "Implement per-username in-process failure tracking with injectable Clock in oryxos-web/src/main/java/io/oryxos/web/auth/LoginFailureTracker.java"
```

## Parallel Example: User Story 3

```text
Task: "Add DataJpaTest coverage for auth event persistence and newest-first bounded query in oryxos-storage/src/test/java/io/oryxos/storage/AdminAuthEventRepositoryTest.java"
Task: "Add MockMvc tests for unconfigured deployment status, denied protected access, and auth event query limits in oryxos-web/src/test/java/io/oryxos/web/auth/AdminAuthOperationsTest.java"
Task: "Add SpringBootTest coverage for unconfigured deployment safety in oryxos-boot/src/test/java/io/oryxos/boot/AdminAuthUnconfiguredIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational.
3. Complete Phase 3: User Story 1.
4. Stop and validate US1 independently with MockMvc, SpringBootTest, and `/admin/` frontend build checks.

### Incremental Delivery

1. Setup + Foundational creates the auth contract, schema, runtime wiring, and JSON security responses.
2. US1 adds normal login, logout, protected management access, CSRF, and the OryxOS-style login page.
3. US2 adds safe failure handling and temporary lockout without changing the successful-login UX.
4. US3 adds unconfigured safety polish and authenticated audit-event retrieval.
5. Polish validates the quickstart commands, sensitive-data boundaries, and frontend visual consistency.

### Single-Developer Order

1. T001-T015
2. T016-T030
3. T031-T036
4. T037-T045
5. T046-T052

---

## Notes

- Keep `/admin/**` static assets anonymous, but never load protected management data until auth status confirms an authenticated session.
- Keep all security failures in the existing `ApiResponse` envelope.
- Do not add JWT, remember-me, registration, password recovery, multi-admin management, RBAC, WebFlux, Reactor, or a new auth module.
- Do not log or persist passwords, password hashes, CSRF tokens, session cookies, or raw request bodies.
- Use manual SQLite DDL in `schema.sql`; do not rely on Hibernate schema update.
