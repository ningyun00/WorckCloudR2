# WorkClockR2 MySQL API

This API implements the Android app's existing contract and uses the local MySQL database.

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
