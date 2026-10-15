const encoder = new TextEncoder();
const TOKEN_TTL_SECONDS = 8 * 60 * 60;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
  });
}
function fail(status, message) { return json({ error: message }, status); }
function b64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}
function unb64(value) {
  const raw = atob(String(value || ""));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function b64url(bytes) {
  return b64(bytes).replace(/=/g, "").split("+").join("-").split("/").join("_");
}
function fromB64url(value) {
  let s = String(value).replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  return unb64(s);
}
async function hmac(secret, value) {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
}
async function issueToken(secret, employee) {
  const payload = b64url(encoder.encode(JSON.stringify({
    sub: employee.employeeId,
    role: employee.role,
    exp: Math.floor(Date.now() / 1000) + TOKEN_TTL_SECONDS,
    nonce: crypto.randomUUID()
  })));
  return payload + "." + b64url(await hmac(secret, payload));
}
async function readToken(request, secret) {
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) return null;
  const parts = header.slice(7).split(".");
  if (parts.length !== 2) return null;
  try {
    const expected = await hmac(secret, parts[0]);
    const supplied = fromB64url(parts[1]);
    if (expected.length !== supplied.length) return null;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ supplied[i];
    if (diff !== 0) return null;
    const p = JSON.parse(new TextDecoder().decode(fromB64url(parts[0])));
    if (!p.sub || !p.exp || p.exp <= Math.floor(Date.now() / 1000)) return null;
    return { employeeId: String(p.sub), role: String(p.role || "") };
  } catch { return null; }
}
async function readJsonObject(bucket, key) {
  const obj = await bucket.get(key);
  if (!obj) return null;
  return JSON.parse(await obj.text());
}
async function employees(bucket) {
  const data = await readJsonObject(bucket, "employees.json");
  if (!data || typeof data !== "object") throw new Error("employee store unavailable");
  return [...(Array.isArray(data.employees) ? data.employees : []), ...(Array.isArray(data.ListUsers) ? data.ListUsers : [])];
}
async function verifyPassword(employee, password) {
  try {
    const salt = unb64(employee.passwordSalt);
    const expected = unb64(employee.passwordHash);
    const combined = new Uint8Array(salt.length + encoder.encode(password).length);
    combined.set(salt);
    combined.set(encoder.encode(password), salt.length);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", combined));
    if (digest.length !== expected.length) return false;
    let diff = 0;
    for (let i = 0; i < digest.length; i++) diff |= digest[i] ^ expected[i];
    return diff === 0;
  } catch { return false; }
}
function chinaDateParts(date = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
  }).formatToParts(date);
  const p = Object.fromEntries(parts.map(x => [x.type, x.value]));
  return { day: p.year + "-" + p.month + "-" + p.day, at: p.year + "-" + p.month + "-" + p.day + "T" + p.hour + ":" + p.minute + ":" + p.second + "+08:00" };
}
function dayKey(day) { return "attendance/" + day.slice(0, 7) + "/" + day + ".json"; }

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET,POST,OPTIONS", "Access-Control-Allow-Headers": "Authorization,Content-Type", "Access-Control-Max-Age": "86400" } });
    const cors = { "Access-Control-Allow-Origin": "*", "Vary": "Origin" };
    const wrap = async response => {
      const headers = new Headers(response.headers);
      for (const [k,v] of Object.entries(cors)) headers.set(k,v);
      return new Response(response.body, { status: response.status, headers });
    };
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return wrap(json({ service: "workclockr2-auth", status: "ok", r2: env.ATTENDANCE_BUCKET ? "bound" : "missing", serializedWrites: !!env.ATTENDANCE_LOCK }));
      }
      if (!env.ATTENDANCE_BUCKET || !env.TOKEN_SECRET || !env.ATTENDANCE_LOCK) return wrap(fail(503, "Service not configured"));
      if (request.method === "POST" && url.pathname === "/api/session") {
        const body = await request.json().catch(() => null);
        if (!body || typeof body.employeeId !== "string" || typeof body.password !== "string" || !body.employeeId || !body.password) return wrap(fail(400, "工号和密码不能为空"));
        const list = await employees(env.ATTENDANCE_BUCKET);
        const employee = list.find(x => typeof x.employeeId === "string" && x.employeeId === body.employeeId && x.enabled === true);
        if (!employee || !(await verifyPassword(employee, body.password))) return wrap(fail(401, "工号或密码错误"));
        return wrap(json({
          token: await issueToken(env.TOKEN_SECRET, employee),
          employee: { employeeId: employee.employeeId, displayName: employee.displayName || "", role: employee.role || "", mustChangePassword: !!employee.mustChangePassword }
        }));
      }
      const session = await readToken(request, env.TOKEN_SECRET);
      if (!session) return wrap(fail(401, "登录已失效，请重新登录"));
      if (request.method === "GET" && url.pathname === "/api/config") {
        const cfg = await readJsonObject(env.ATTENDANCE_BUCKET, "app-config.json");
        return wrap(json({ allowedSsid: cfg && typeof cfg.allowedSsid === "string" ? cfg.allowedSsid : "" }));
      }
      if (request.method === "GET" && url.pathname === "/api/attendance") {
        const month = url.searchParams.get("month");
        if (month !== null) {
          if (!/^\d{4}-\d{2}$/.test(month) || !Number.isFinite(Date.parse(month + "-01T00:00:00Z"))) return wrap(fail(400, "月份格式无效"));
          const legacy = await readJsonObject(env.ATTENDANCE_BUCKET, "attendance/" + month + ".json");
          const records = Array.isArray(legacy) ? legacy : [];
          const year = Number(month.slice(0, 4)), mon = Number(month.slice(5, 7));
          const daysInMonth = new Date(Date.UTC(year, mon, 0)).getUTCDate();
          const dailyRecords = [];
          for (let d = 1; d <= daysInMonth; d++) {
            const day = month + "-" + String(d).padStart(2, "0");
            const daily = await readJsonObject(env.ATTENDANCE_BUCKET, dayKey(day));
            if (Array.isArray(daily)) dailyRecords.push(...daily);
          }
          const all = dailyRecords.length ? dailyRecords : records;
          return wrap(json({ records: all.filter(x => x && x.employeeId === session.employeeId && x.day.startsWith(month)) }));
        }
        const day = url.searchParams.get("day") || chinaDateParts().day;
        if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || !Number.isFinite(Date.parse(day + "T00:00:00Z"))) return wrap(fail(400, "日期格式无效"));
        const daily = await readJsonObject(env.ATTENDANCE_BUCKET, dayKey(day));
        let records = Array.isArray(daily) ? daily : null;
        if (!records) {
          const legacy = await readJsonObject(env.ATTENDANCE_BUCKET, "attendance/" + day.slice(0, 7) + ".json");
          records = Array.isArray(legacy) ? legacy.filter(x => x.day === day) : [];
        }
        return wrap(json({ records: records.filter(x => x && x.employeeId === session.employeeId) }));
      }
      if (request.method === "POST" && url.pathname === "/api/attendance") {
        const body = await request.json().catch(() => null);
        if (!body || typeof body.id !== "string" || !body.id || body.id.length > 128 || !["IN", "OUT", "TEMP_OUT"].includes(body.type)) return wrap(fail(400, "打卡数据无效"));
        const when = chinaDateParts();
        const record = { id: body.id, employeeId: session.employeeId, at: when.at, type: body.type, day: when.day };
        const lock = env.ATTENDANCE_LOCK.get(env.ATTENDANCE_LOCK.idFromName("attendance-global"));
        const saved = await lock.fetch("https://lock.internal/append", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(record) });
        if (!saved.ok) return wrap(fail(500, "打卡保存失败，请稍后重试"));
        return wrap(json({ record }));
      }
      if (request.method === "POST" && url.pathname === "/api/admin/attendance/makeup") {
        if (session.role !== "admin") return wrap(fail(403, "仅管理员可以添加补班记录"));
        const body = await request.json().catch(() => null);
        if (!body || !Array.isArray(body.records) || body.records.length < 2 || body.records.length > 100) return wrap(fail(400, "补班记录格式无效"));
        const list = await employees(env.ATTENDANCE_BUCKET);
        const enabledIds = new Set(list.filter(x => x && x.enabled === true).map(x => String(x.employeeId)));
        const seen = new Set();
        for (const record of body.records) {
          if (!record || typeof record.id !== "string" || !record.id || record.id.length > 128 ||
              typeof record.employeeId !== "string" || !enabledIds.has(record.employeeId) ||
              !/^\d{4}-\d{2}-\d{2}$/.test(record.day) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(record.at) ||
              !["IN", "OUT"].includes(record.type) || seen.has(record.id)) {
            return wrap(fail(400, "补班记录内容无效"));
          }
          seen.add(record.id);
        }
        const lock = env.ATTENDANCE_LOCK.get(env.ATTENDANCE_LOCK.idFromName("attendance-global"));
        const saved = await lock.fetch("https://lock.internal/append-many", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ records: body.records }) });
        const result = await saved.json().catch(() => null);
        if (!saved.ok) return wrap(fail(500, result && result.error === "Duplicate punch ID" ? "补班记录编号重复，请重新提交" : "补班保存失败，请重试"));
        return wrap(json({ ok: true, count: body.records.length }));
      }
      if (request.method === "POST" && url.pathname === "/api/password") {
        const body = await request.json().catch(() => null);
        if (!body || typeof body.currentPassword !== "string" || typeof body.newPassword !== "string" || body.newPassword.length < 6 || body.newPassword.length > 256) return wrap(fail(400, "新密码至少 6 位，且不能超过 256 个字符"));
        const current = (await employees(env.ATTENDANCE_BUCKET)).find(x => x.employeeId === session.employeeId && x.enabled === true);
        if (!current || !(await verifyPassword(current, body.currentPassword))) return wrap(fail(401, "当前密码错误"));
        const salt = crypto.getRandomValues(new Uint8Array(16));
        const pw = encoder.encode(body.newPassword);
        const combined = new Uint8Array(salt.length + pw.length);
        combined.set(salt); combined.set(pw, salt.length);
        const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", combined));
        const lock = env.ATTENDANCE_LOCK.get(env.ATTENDANCE_LOCK.idFromName("attendance-global"));
        const saved = await lock.fetch("https://lock.internal/change-password", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ employeeId: session.employeeId, passwordSalt: b64(salt), passwordHash: b64(hash) }) });
        if (!saved.ok) return wrap(fail(500, "密码保存失败，请重试"));
        return wrap(json({ ok: true }));
      }
      return wrap(fail(404, "Not found"));
    } catch {
      return wrap(fail(500, "服务暂时不可用，请稍后重试"));
    }
  }
};

export class AttendanceLock {
  constructor(state, env) { this.state = state; this.env = env; this.queue = Promise.resolve(); }
  async fetch(request) {
    const url = new URL(request.url);
    const job = async () => {
      if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
      const body = await request.json().catch(() => null);
      if (!body) return json({ error: "Invalid body" }, 400);
      if (url.pathname === "/append") {
        if (!body.day || !body.employeeId || !body.id) return json({ error: "Invalid record" }, 400);
        const key = dayKey(body.day);
        let records = await readJsonObject(this.env.ATTENDANCE_BUCKET, key);
        if (!Array.isArray(records)) {
          const legacy = await readJsonObject(this.env.ATTENDANCE_BUCKET, "attendance/" + body.day.slice(0, 7) + ".json");
          records = Array.isArray(legacy) ? legacy.filter(x => x.day === body.day) : [];
        }
        if (records.some(x => x.id === body.id)) return json({ error: "Duplicate punch ID" }, 409);
        records.push(body);
        await this.env.ATTENDANCE_BUCKET.put(key, JSON.stringify(records), { httpMetadata: { contentType: "application/json; charset=utf-8" } });
        const check = await readJsonObject(this.env.ATTENDANCE_BUCKET, key);
        if (!Array.isArray(check) || !check.some(x => x.id === body.id)) return json({ error: "Write verification failed" }, 500);
        return json({ records: check });
      }
      if (url.pathname === "/append-many") {
        if (!Array.isArray(body.records) || body.records.length < 2) return json({ error: "Invalid records" }, 400);
        const byDay = new Map();
        for (const record of body.records) {
          if (!record || typeof record.day !== "string" || typeof record.employeeId !== "string" || typeof record.id !== "string") return json({ error: "Invalid record" }, 400);
          if (!byDay.has(record.day)) byDay.set(record.day, []);
          byDay.get(record.day).push(record);
        }
        for (const [day, additions] of byDay) {
          const key = dayKey(day);
          let current = await readJsonObject(this.env.ATTENDANCE_BUCKET, key);
          if (!Array.isArray(current)) {
            const legacy = await readJsonObject(this.env.ATTENDANCE_BUCKET, "attendance/" + day.slice(0, 7) + ".json");
            current = Array.isArray(legacy) ? legacy.filter(x => x.day === day) : [];
          }
          const ids = new Set(current.map(x => x.id));
          if (additions.some(x => ids.has(x.id))) return json({ error: "Duplicate punch ID" }, 409);
          current.push(...additions);
          await this.env.ATTENDANCE_BUCKET.put(key, JSON.stringify(current), { httpMetadata: { contentType: "application/json; charset=utf-8" } });
          const check = await readJsonObject(this.env.ATTENDANCE_BUCKET, key);
          if (!Array.isArray(check) || additions.some(x => !check.some(y => y.id === x.id))) return json({ error: "Write verification failed" }, 500);
        }
        return json({ ok: true, count: body.records.length });
      }
      if (url.pathname === "/change-password") {
        if (typeof body.employeeId !== "string" || typeof body.passwordSalt !== "string" || typeof body.passwordHash !== "string") return json({ error: "Invalid request" }, 400);
        const current = await readJsonObject(this.env.ATTENDANCE_BUCKET, "employees.json");
        if (!current || typeof current !== "object") return json({ error: "Employee store unavailable" }, 500);
        let found = false;
        for (const group of ["employees", "ListUsers"]) {
          if (!Array.isArray(current[group])) continue;
          current[group] = current[group].map(employee => {
            if (employee && employee.employeeId === body.employeeId) {
              found = true;
              return { ...employee, passwordSalt: body.passwordSalt, passwordHash: body.passwordHash, mustChangePassword: false };
            }
            return employee;
          });
        }
        if (!found) return json({ error: "Employee not found" }, 404);
        await this.env.ATTENDANCE_BUCKET.put("employees.json", JSON.stringify(current), { httpMetadata: { contentType: "application/json; charset=utf-8" } });
        const check = await readJsonObject(this.env.ATTENDANCE_BUCKET, "employees.json");
        const verified = [...(Array.isArray(check.employees) ? check.employees : []), ...(Array.isArray(check.ListUsers) ? check.ListUsers : [])].find(x => x.employeeId === body.employeeId);
        if (!verified || verified.passwordSalt !== body.passwordSalt || verified.passwordHash !== body.passwordHash) return json({ error: "Write verification failed" }, 500);
        return json({ ok: true });
      }
      return json({ error: "Not found" }, 404);
    };
    const run = this.queue.then(job, job);
    this.queue = run.then(() => undefined, () => undefined);
    return run;
  }
}
