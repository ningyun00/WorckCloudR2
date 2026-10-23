/**
 * Shared JSON readers for the WorkClockR2 Worker and AttendanceLock.
 * Missing R2 objects return null; malformed JSON is deliberately surfaced.
 */
export async function readJsonObject(bucket, key) {
  const object = await bucket.get(key);
  if (!object) return null;
  return JSON.parse(await object.text());
}

export async function employees(bucket) {
  const data = await readJsonObject(bucket, "employees.json");
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new Error("employee store unavailable");
  }
  return [
    ...(Array.isArray(data.employees) ? data.employees : []),
    ...(Array.isArray(data.ListUsers) ? data.ListUsers : [])
  ];
}
