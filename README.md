# WorkClock R2 Android App

Kotlin + Jetpack Compose starter project with GitHub Actions cloud APK build.

## Build an APK
1. Open the repository's **Actions** tab.
2. Run **Build Android APK** (or push to main/master).
3. Download artifact **WorkClockR2-debug-apk** and extract `app-debug.apk`.

## Important limitations
- This is a starter build, not yet verified on a real device or against your R2 bucket.
- Direct R2 credentials inside an APK can be extracted. Use a dedicated token scoped to one bucket with only required permissions; never use your Cloudflare global API key.
- Concurrent read-modify-write updates to a shared JSON object can overwrite another employee's punch. A Worker API is recommended for production multi-user usage.
- Wi-Fi SSID checks require Android permissions and are not strong authentication; SSIDs can be spoofed.
- Upload `employees.json` to the bucket before employee login. See `R2_EMPLOYEES_TEMPLATE.json`.
- The current starter does not yet provide in-app administrator employee creation or password-change writes; initial-password changes require editing the JSON object as requested.

## R2 JSON objects
- `employees.json`: employee roles and salted password hashes.
- `attendance/YYYY-MM.json`: monthly punch log array.

Use a dedicated test bucket before production.
