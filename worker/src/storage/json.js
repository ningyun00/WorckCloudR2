/**
 * Shared JSON readers for the WorkClockR2 Worker and AttendanceLock.
 * Missing R2 objects return null; malformed JSON is deliberately surfaced.
 */
const CONFIG_CACHE_TTL_MS = 2000;
let configCache = { value: undefined, expiresAt: 0, pending: null };

export async function readJsonObject(bucket, key) {
  // Config is read often but changes rarely. A very short isolate-local cache
  // coalesces bursts while keeping admin changes visible almost immediately.
  if (key === "app-config.json") {
    const now = Date.now();
    if (configCache.value !== undefined && now < configCache.expiresAt) {
      return configCache.value;
    }
    if (configCache.pending) return configCache.pending;
    configCache.pending = readFromR2(bucket, key)
      .then(value => {
        configCache.value = value;
        configCache.expiresAt = Date.now() + CONFIG_CACHE_TTL_MS;
        return value;
      })
      .finally(() => { configCache.pending = null; });
    return configCache.pending;
  }
  return readFromR2(bucket, key);
}

async function readFromR2(bucket, key) {
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
