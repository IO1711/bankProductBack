# Bank product passport approval API (demo)

This is a runnable Spring Boot API for the loan/deposit passport workflow in the project specification. There is no frontend. Use Postman or another HTTP client.

## Start

Requires Java 21+ and network access on the first Maven run. From this directory, run the server in a terminal:

```bash
./run-local.sh
```

The script packages the current sources and replaces itself with the foreground Java process. Press Ctrl+C in **that same terminal** to stop it. A server started by a detached Codex command has no controlling terminal; Ctrl+C in another terminal cannot stop that process.

The API listens on `http://localhost:8080` and uses the local PostgreSQL database `bank_product` at localhost:5432 for demo testing. The supplied credentials are in the ignored `.env.local` file; edit `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` there to change the connection. PostgreSQL must be running and the database and role must exist. Set `APP_DATA_DIR` to change the upload directory and `APP_PUBLIC_BASE_URL` when testing QR links from another machine. In the default document demo mode, LibreOffice (`soffice`) converts DOC/DOCX files to PDF when available; if it is missing, the generated PDF is clearly labeled as a demo placeholder. A conversion failure after LibreOffice starts returns 422.

```bash
./mvnw test
```

Import [postman/Bank Product Flow.postman_collection.json](postman/Bank%20Product%20Flow.postman_collection.json) into Postman. Set its `baseUrl` to your server. Reimport the collection if you used its earlier `X-Demo-User` version.

Before calling protected endpoints, run **Authentication → Get CSRF token → Issue demo host ticket → Exchange ticket for session**. The fake host ticket lasts 60 seconds and works once. Postman keeps the `JSESSIONID` cookie for subsequent requests; the collection adds the CSRF header to writes. To switch users, change `userId`, issue a new ticket, and exchange it. After logout, fetch a new CSRF token before logging in again.

## Demo boundaries

The API uses a Spring Security authenticated session. `X-Demo-User` is ignored. `POST /api/auth/sso` consumes a one-time ticket through `HostTicketValidator`; `OrganizationDirectory` supplies user and department data. The demo implementation serves both ports, and the workflow refreshes the user's current organization attributes on each request. `/api/demo/host/tickets` and `/api/demo/users` exist only when `app.host.mode=demo`. A real deployment needs the bank's ticket validation and organization adapters; setting a different host mode without those beans prevents startup. Sessions currently live in this server's memory. The session cookie is HttpOnly and SameSite=Lax; set `SESSION_COOKIE_SECURE=true` behind HTTPS. Mutating requests require a CSRF token from `/api/auth/csrf`.

Signing has a replaceable verification provider. For Postman, call `prepare`, `POST /api/demo/eimzo/sign` with the returned `sign_session_id`, then send its `pkcs7` value and session ID to the action. The demo value is an HMAC-protected test token, **not PKCS#7 or a legal signature**. The server checks the prepared document hash, session, demo certificate PINFL, and single use before advancing the workflow. Generated PDFs and QR pages remain labeled demo. The QR page checks its saved PDF snapshot hash, not PKCS#7. The document scanner is a fake adapter, and there is no bank notification bridge. Do not expose this demo service to untrusted users or use it for actual approvals.

The replacement point is `SignatureVerificationProvider.verify(pkcs7, dataToSign, signingSessionId)`. A bank adapter must verify the detached PKCS#7 over the supplied 32 SHA-256 bytes, certificate chain, validity/revocation and any required timestamp, and return the verified certificate PINFL, serial number, signing time, and signed document hash. `SigningService` compares those returned values with the current host user and prepared document. Set `app.eimzo.mode` away from `demo` only after registering that adapter; without a verifier bean the app fails to start rather than accepting unverified signatures. The bank's E-IMZO server API, accepted certificate types, timestamp policy, and domain/API key are still required to implement the adapter. Signature evidence is stored with each document in the database.

Create requests with a non-head demo employee such as `u-creator`. The fixed demo organization prevents a department head or chairman from creating a request because that person would otherwise be asked to sign their own passport twice. `u-exec-head` is available if you choose `D-20` as a receiving department.

Flyway creates the workflow tables on startup. Requests, receivers, participants, document metadata, signatures, reviews, and audit events are stored in the database; uploads and generated PDFs stay in `data/files`. API mutations run in database transactions, lock the request row, and check its version. When a new empty database starts beside an old `data/state.bin`, its contents are imported once, with new numeric IDs; the original file is kept. Set `APP_IMPORT_LEGACY_STATE=false` to skip this import. Keep `data/files` with its database, since document rows refer to those files. The PDF original and each signature version are retained; signed documents cannot be deleted through the API. Old cycles remain visible in request history, and their QR pages say the signatures are superseded. DOCX uploads must be valid ZIP-based Word files; legacy DOC uploads need the OLE file header. The final ZIP streams its current documents rather than loading the archive into memory.

## Document pipeline

Conversion, storage, and scanning are separate interfaces: `WordToPdfConverter`, `DocumentFileStorage`, and `AntivirusScanner`. The default `app.document.mode=demo` uses local file storage, a fake scanner, and a converter that uses LibreOffice when available and otherwise creates a labeled placeholder. For an otherwise valid Word upload, the fake scanner rejects the `DEMO_VIRUS` byte marker so its rejection path can be tested; it does not detect real malware. Uploads are scanned before storage, and downloads and final ZIPs read through the storage interface.

Set `APP_DOCUMENT_MODE=real` when registering real implementations of all three interfaces. Demo adapters are disabled in that mode. Without a `WordToPdfConverter` implementation, application startup fails; real mode cannot generate a placeholder PDF. A real converter should reject unavailable or failed conversion instead of returning a demo artifact. The HTTP upload, download, and signing endpoints keep the same contract across modes.

## Postman workflow

Use the collection's `userId` variable to choose the user **when issuing a new ticket**. Exchange that ticket before acting as the user. Its Create request saves `requestId` automatically; the Upload request saves `documentId`; Prepare saves `signSessionId`; Demo E-IMZO sign saves `demoPkcs7`. For a signed action, set `action`, call Prepare, Demo E-IMZO sign, then Run. Run includes the session ID and demo token; edit its other parameters as needed. `review_head_sign` also needs the correct `review_task_id` in **both** Prepare and Run. Use `GET /api/requests/{{requestId}}` to get task IDs and current documents. Upload uses multipart `kind` plus a real `.doc` or `.docx` file, at most 50 MB.

| Step | `userId` | Action / input | Expected request status |
| --- | --- | --- | --- |
| 1 | `u-creator` | Create `{"type":"loan"}` or `deposit`, upload `passport`, sign `submit_passport` | `PASSPORT_WAIT_HEAD` |
| 2 | `u-head` | Sign `head_approve`, or run `head_reject` with `reason` | `PASSPORT_APPROVED` or new cycle |
| 3 | `u-creator` | Upload `letter`, sign `submit_letter` with `department_ids:["D-12","D-15"]` | `LETTER_WAIT_HEAD` |
| 4 | `u-head` | Sign `letter_approve`, or run `letter_return` with `reason` | `REVIEW_ASSIGNING` or `LETTER_RETURNED` |
| 5 | `u-risk-head`, `u-legal-head` | Each runs `assign` with `user_id` `u-risk` / `u-legal` | `REVIEW_IN_PROGRESS` |
| 6 | `u-risk`, `u-legal` | Each uploads `review`, signs `submit_review` with `conclusion:"positive"` or `"negative"` | `REVIEW_WAIT_HEAD` |
| 7 | `u-risk-head`, `u-legal-head` | Each signs `review_head_sign` with its `review_task_id` | `CREATOR_HEAD_ASSIGNING` after all positive reviews; new cycle if any negative |
| 8 | `u-head` | Run `assign` with `user_id:"u-notice"` | `NOTICE_DRAFT` |
| 9 | `u-notice` | Upload `notice`, sign `submit_notice` | `NOTICE_WAIT_HEAD` |
| 10 | `u-head` | Sign `notice_approve`, or run `notice_reject` with `reason` | `WAIT_CHAIRMAN` or `NOTICE_RETURNED` |
| 11 | `u-chair` | Sign `chairman_approve` with `executor_department_id:"D-20"`, or run `chairman_reject` with `reason` | `COMPLETED` or new cycle |
| 12 | `u-exec` | Download `/api/requests/{{requestId}}/package` | ZIP with current passport, reviews, notice |

`head_reject`, `letter_return`, `notice_reject`, `chairman_reject`, and `assign` have no Prepare step. Rejections require a nonempty reason. Passport/head, negative review, and chairman rejections start a new cycle while retaining old documents. Letter and notice returns allow a replacement document within the current cycle.

## Key endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Obtain a CSRF token for writes |
| POST | `/api/demo/host/tickets` | Issue a fake one-time SSO ticket |
| POST | `/api/auth/sso`, `/api/auth/logout` | Start or end an authenticated session |
| GET | `/api/me`, `/api/demo/users` | Current authenticated user, all fake host users |
| GET | `/api/departments`, `/api/departments/{id}/employees` | Organization directory |
| POST | `/api/requests` | Create a loan/deposit request |
| GET | `/api/requests?scope=mine`, `?scope=inbox` | Owned requests, actionable inbox |
| GET | `/api/requests/{id}` | Detail, documents, review tasks, history, `available_actions` |
| GET | `/api/requests?scope=completed` | Completed requests visible to the current participant, including executor department employees |
| POST | `/api/requests/{id}/documents` | Multipart upload (`kind`, `file`) |
| GET | `/api/documents/{id}/download?original=true` | Signed PDF or original DOC/DOCX |
| DELETE | `/api/documents/{id}` | Remove own unsigned document from current cycle (204) |
| POST | `/api/requests/{id}/actions/{action}/prepare` | Create 5-minute demo signing session |
| POST | `/api/demo/eimzo/sign` | Issue a fake signature for a prepared session and selected demo user |
| POST | `/api/requests/{id}/actions/{action}` | Execute signed or unsigned action |
| GET | `/api/notifications/summary` | Count of requests with an action for the authenticated user |
| GET | `/api/requests/{id}/package` | Final ZIP after completion |
| GET | `/verify/{code}` | Public demo QR verification page |
| GET | `/api/audit` | Demo audit events (`u-chair` only) |

All participant access and available actions are checked on the server. Errors use RFC 9457 `application/problem+json`; APIs return 401 without a session or for invalid tickets, 403 for role or CSRF failures, 409 for state conflicts, and 422 for invalid signing sessions or failed DOC/DOCX conversion. The generated PDF is a new file per signature. The hash shown on `/verify/{code}` applies to that signature's PDF snapshot. If an action fails validation, its demo signing session can be retried until it expires; successful sessions can be used only once.
