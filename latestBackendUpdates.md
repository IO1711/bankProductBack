# Latest backend updates for frontend integration

Updated: 2026-10-29. Keep this file current when a backend change affects frontend requests, responses, or user flows. The backend is an API only; use the Postman collection for the current runnable flow.

## Frontend changes to plan

1. **Replace `X-Demo-User` with session login.** The header is ignored. Before a write, call `GET /api/auth/csrf`, retain its `token` and `header_name` (currently `X-CSRF-TOKEN`), and send that header on `POST` and `DELETE` requests. Preserve the `JSESSIONID` cookie on all requests. For the current demo, issue a one-time ticket with `POST /api/demo/host/tickets` and exchange it with `POST /api/auth/sso`. Use `GET /api/me` to load the signed-in user and department; use `POST /api/auth/logout` to end the session. The demo ticket lasts 60 seconds and can be exchanged once. Fetch a new CSRF token after logout. The frontend should keep the host ticket source replaceable for real bank SSO later.

2. **Use the signed-action sequence.** For actions requiring a signature, call `POST /api/requests/{id}/actions/{action}/prepare` with any action-specific fields. It returns `sign_session_id`, `data_to_sign` (Base64-encoded SHA-256 bytes), `hash_alg`, `document_id`, and `demo`. In this demo, send `{"sign_session_id":"..."}` to `POST /api/demo/eimzo/sign` as the same signed-in user; it returns `pkcs7` and `demo: true`. Then call `POST /api/requests/{id}/actions/{action}` with `sign_session_id`, `pkcs7`, and the action-specific fields. Signing sessions expire after five minutes and are consumed after a successful action. The demo token is simulated and is not a legal PKCS#7 signature. Real E-IMZO integration will replace the demo signing step.

3. **Drive workflow controls from the server response.** `GET /api/requests/{id}` and request list items include `available_actions` for the current user. Use these values to show actions; the backend checks roles, stage, and document ownership again when an action runs. The detail response also includes `status`, `cycle`, `reason`, `documents`, `review_tasks`, `history`, `receiver_departments`, `notice_assignee`, and `executor_department_id`. Each document exposes `signed_by` entries with signer name, time, verification code, hashes, and `verification_mode`. A review head needs `review_task_id` from `review_tasks` in both the prepare and execute bodies for `review_head_sign`.

4. **Treat request and document IDs as database IDs.** Workflow metadata, review tasks, signatures, and audit events now persist in local PostgreSQL `bank_product` through Flyway migrations and transactional repositories. Uploaded files and generated PDFs remain in `data/files`. Existing `data/state.bin` is imported once when the database is empty; imported numeric IDs may differ from old demo IDs. Do not cache old IDs across this migration. The previous file database run path was removed; H2 is used only by automated tests.

5. **Handle document pipeline failures.** Upload scanning, file storage, and Word-to-PDF conversion now use replaceable backend adapters; the HTTP endpoints and response shapes are unchanged. The default `app.document.mode=demo` uses a fake scanner and local storage. An otherwise valid Word upload containing the `DEMO_VIRUS` byte marker is rejected with 422 before storage. The demo converter uses LibreOffice when available and otherwise creates a labeled placeholder. `APP_DOCUMENT_MODE=real` disables all demo adapters and refuses startup until real adapters are registered, including a converter; real mode cannot silently create a placeholder PDF. Show upload/conversion error details from `application/problem+json`.

## Current HTTP contract

| Area | Endpoint | Frontend use |
| --- | --- | --- |
| Authentication | `GET /api/auth/csrf` | Get `{header_name, token}` and establish the session cookie. |
| Demo SSO | `POST /api/demo/host/tickets` with `{"user_id":"u-creator"}` | Get a one-time `{ticket, expires_in_seconds, demo}`. This route is demo only and still needs CSRF. |
| Authentication | `POST /api/auth/sso` with `{"ticket":"..."}` | Get `{user, authenticated:true}` and keep the session cookie. |
| Authentication | `GET /api/me`; `POST /api/auth/logout` | Load `{user, department, authenticated:true}`; logout returns `{authenticated:false}`. |
| Organization | `GET /api/departments`; `GET /api/departments/{id}/employees` | Load departments and employees from the host provider. `/api/demo/users` lists fake users only in demo mode. |
| Requests | `POST /api/requests` with `{"type":"loan"}` or `deposit` | Create a request; returns its detail with HTTP 201. |
| Requests | `GET /api/requests?scope=mine`; `GET /api/requests/{id}` | Load request summaries or detail. Other scopes are `inbox` and `completed`; `mine` is the default. |
| Documents | `POST /api/requests/{id}/documents` | Multipart fields `kind` (`passport`, `letter`, `review`, `notice`) and `file` (real DOC/DOCX, up to 50 MB); returns document metadata with HTTP 201. |
| Documents | `GET /api/documents/{id}/download?original=true`; `DELETE /api/documents/{id}` | Download the original or current PDF; delete only the uploader's unsigned current document. |
| Actions | `POST /api/requests/{id}/actions/{action}/prepare`; `POST /api/requests/{id}/actions/{action}` | Prepare signed actions, then execute signed or unsigned actions. |
| Demo signing | `POST /api/demo/eimzo/sign` | Get a simulated `pkcs7` value for a prepared signing session. Available only in demo mode. |
| Other | `GET /api/notifications/summary`; `GET /api/requests/{id}/package` | Get `{count}` of actionable requests; download the final ZIP after completion. |
| Verification | `GET /verify/{code}` | Public HTML verification page for a signature code; it is not a JSON API. |

Signed actions: `submit_passport`, `head_approve`, `submit_letter`, `letter_approve`, `submit_review`, `review_head_sign`, `submit_notice`, `notice_approve`, `chairman_approve`. Unsigned actions: `head_reject`, `letter_return`, `assign`, `notice_reject`, `chairman_reject`.

Action-specific fields: `submit_letter` needs `department_ids` (array of department IDs); `assign` needs `user_id`; `submit_review` needs `conclusion` (`positive` or `negative`) and can identify the review document with `document_id`; `review_head_sign` needs `review_task_id`; `chairman_approve` needs `executor_department_id`; rejection/return actions need a nonempty `reason`. A signed action also needs `sign_session_id` and `pkcs7`. Upload the required document before preparing its action.

## Error and integration notes

- API errors use `application/problem+json`. Expect 401 for missing/invalid session, 403 for forbidden actions or missing CSRF, 409 for workflow or concurrent-change conflicts, and 422 for an invalid signing session or failed document conversion. Refresh request detail after a 409.
- Session and signing challenge state are currently in server memory; workflow data is in PostgreSQL. The current session cookie is HttpOnly, SameSite=Lax, with a 30-minute session timeout. Enable secure cookies behind HTTPS with `SESSION_COOKIE_SECURE=true`.
- The backend does not currently configure CORS. A frontend on a separate origin will need a same-origin proxy or a backend CORS change before browser calls will work.
- Demo host users and tickets, demo E-IMZO signatures, and demo PDF conversion fallback are temporary provider implementations. The public QR page checks the saved PDF snapshot hash; it does not perform legal signature validation.

See [README.md](README.md) and [postman/Bank Product Flow.postman_collection.json](postman/Bank%20Product%20Flow.postman_collection.json) for a complete manual test flow.
