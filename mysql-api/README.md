# WorkClockR2 MySQL API

This API preserves the Android app's existing request/response contract while replacing the backend implementation with a validated, transaction-safe MySQL service. Android UI changes are intentionally out of scope for this backend refactor.

## Local setup
1. Copy this folder to `C:\\WorckCloudR2-MySQL` (or use the existing folder).
2. Run `npm.cmd install`.
3. Copy `.env.example` to `.env` and fill in the local MySQL password and a long random TOKEN_SECRET. Never commit `.env`.
4. Ensure the schema has been applied to database `hlsxcs`.
5. Run `npm.cmd start`.
6. Check `https://hlcs.sixiaojie.dpdns.org/health`. Expected: `{"status":"ok","database":"connected"}`.

## Routes
- `POST /api/session`
- `GET /api/config`
- `GET /api/attendance?day=YYYY-MM-DD`
- `GET /api/attendance?month=YYYY-MM`
- `POST /api/attendance`
- `POST /api/admin/attendance/makeup`
- `POST /api/password`

Employee password hashes use the existing Worker format: SHA-256(Base64-decoded salt bytes + UTF-8 password), with Base64 salt/hash fields. Times are stored as local China time in MySQL and returned in the app's expected string format. Make-up records are validated and written in one SQL transaction.

## Compatibility and safety
- Existing routes and response fields are preserved so the current Android client can keep working without a UI rewrite.
- Employee password hashes remain compatible with the existing Worker format: SHA-256(Base64-decoded salt bytes + UTF-8 password), with Base64 salt/hash fields.
- Session tokens expire after 8 hours; protected requests re-check that the employee exists and remains enabled.
- Admin role matching is case-insensitive (admin / ADMIN).
- Login attempts are rate-limited per source IP; JSON bodies are size-limited.
- Calendar dates and make-up timestamps are validated, including real calendar dates and matching day/at values.
- Make-up entries are written in one SQL transaction; duplicates or invalid employee references fail without partially saving the batch.
- Times remain China-local (Asia/Shanghai) and retain the app's existing string format.
- Health checks verify connectivity and the expected tables. They do not prove employee login or every attendance workflow works.

## Current API contract
- POST /api/session accepts employeeId and password; success returns token and employee.
- GET /api/config returns allowedSsid.
- GET /api/attendance?day=YYYY-MM-DD and GET /api/attendance?month=YYYY-MM return records.
- POST /api/attendance accepts id and type (IN, OUT, TEMP_OUT).
- POST /api/admin/attendance/makeup accepts records and is admin-only.
- POST /api/password accepts currentPassword and newPassword.

Errors keep the existing error field; selected validation and authorization errors also include a stable code field.


## One-time admin bootstrap
- Set `BOOTSTRAP_SECRET` in the local `.env` to a private random string of at least 32 characters. Never commit it or share it.
- Restart the API, then send `POST /internal/bootstrap-admin` with header `X-Bootstrap-Secret: <your secret>` and JSON body `{"employeeId":"admin","displayName":"四小姐","password":"123456"}`.
- This endpoint only creates an admin when no employee with role `admin` exists. It refuses to reset an existing admin and returns no password/hash.
- After successful initialization, remove `BOOTSTRAP_SECRET` from `.env` and restart the API. Change the temporary password promptly; `123456` is not suitable for production.
