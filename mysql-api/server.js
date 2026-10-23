import express from "express";
import mysql from "mysql2/promise";
import dotenv from "dotenv";
import crypto from "node:crypto";

dotenv.config();
const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "256kb" }));
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type");
  res.setHeader("Cache-Control", "no-store");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

const pool = mysql.createPool({
  host: process.env.DB_HOST || "127.0.0.1",
  port: Number(process.env.DB_PORT || 3306),
  database: process.env.DB_NAME || "hlsxcs",
  user: process.env.DB_USER || "hluser",
  password: process.env.DB_PASSWORD,
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0,
  timezone: "+08:00",
  dateStrings: true
});
const TOKEN_SECRET = process.env.TOKEN_SECRET || "";
const fail = (res, status, error, code) => res.status(status).json({ error, ...(code ? { code } : {}) });
const isRealDate = value => {
  if (typeof value !== "string" || !/^\\d{4}-\\d{2}-\\d{2}$/.test(value)) return false;
  const d = new Date(value + "T00:00:00Z");
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
};
const isRealDateTime = value => {
  if (typeof value !== "string" || !/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$/.test(value)) return false;
  if (!isRealDate(value.slice(0, 10))) return false;
  const [, h, m, sec] = value.match(/T(\\d{2}):(\\d{2}):(\\d{2})$/) || [];
  return h !== undefined && Number(h) < 24 && Number(m) < 60 && Number(sec) < 60;
};
const normalizeRole = role => String(role || "").trim().toLowerCase();
const loginAttempts = new Map();
const loginRateLimit = (req, res, next) => {
  const key = req.ip || req.socket.remoteAddress || "unknown";
  const now = Date.now();
  const current = loginAttempts.get(key);
  if (!current || now - current.startedAt > 10 * 60 * 1000) {
    loginAttempts.set(key, { startedAt: now, count: 1 });
    return next();
  }
  if (current.count >= 30) return fail(res, 429, "登录尝试过于频繁，请稍后再试", "RATE_LIMITED");
  current.count += 1;
  next();
};
const chinaParts = () => {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
  }).formatToParts(new Date()).map(x => [x.type, x.value]));
  return { day: `${p.year}-${p.month}-${p.day}`, at: `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}:${p.second}+08:00` };
};
const b64url = value => Buffer.from(value).toString("base64url");
function issueToken(employee) {
  const payload = b64url(JSON.stringify({ sub: String(employee.employee_id), role: employee.role || "", exp: Math.floor(Date.now() / 1000) + 8 * 60 * 60, nonce: crypto.randomUUID() }));
  return payload + "." + crypto.createHmac("sha256", TOKEN_SECRET).update(payload).digest("base64url");
}
function readToken(req) {
  const header = req.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) return null;
  const [payload, signature, extra] = header.slice(7).split(".");
  if (!payload || !signature || extra) return null;
  const expected = crypto.createHmac("sha256", TOKEN_SECRET).update(payload).digest();
  const supplied = Buffer.from(signature, "base64url");
  if (expected.length !== supplied.length || !crypto.timingSafeEqual(expected, supplied)) return null;
  try {
    const p = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    return p.sub && p.exp > Math.floor(Date.now() / 1000) ? { employeeId: String(p.sub), role: String(p.role || "") } : null;
  } catch { return null; }
}
function passwordDigest(saltB64, password) {
  return crypto.createHash("sha256").update(Buffer.concat([Buffer.from(saltB64, "base64"), Buffer.from(password, "utf8")])).digest();
}
function verifyPassword(employee, password) {
  try {
    const expected = Buffer.from(employee.password_hash, "base64");
    const actual = passwordDigest(employee.password_salt, password);
    return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
  } catch { return false; }
}
const publicEmployee = row => ({
  employeeId: String(row.employee_id), displayName: row.display_name || "",
  role: row.role || "", mustChangePassword: !!row.must_change_password
});
async function authenticated(req, res, next) {
  const session = readToken(req);
  if (!session) return fail(res, 401, "登录已失效，请重新登录");
  try {
    const [rows] = await pool.execute("SELECT employee_id,display_name,enabled,must_change_password,password_hash,password_salt,role FROM employees WHERE employee_id=? LIMIT 1", [session.employeeId]);
    const employee = rows[0];
    if (!employee || !employee.enabled) return fail(res, 401, "账号已停用或不存在");
    req.session = { employeeId: String(employee.employee_id), role: normalizeRole(employee.role) };
    req.employee = employee;
    next();
  } catch (e) { console.error("auth query failed", e.code || e.message); fail(res, 500, "读取员工信息失败"); }
}
app.get("/", (_req, res) => res.json({ service: "workclockr2-mysql-api", status: "ok" }));
app.get("/health", async (_req, res) => {
  try {
    await pool.query("SELECT 1");
    await pool.query("SELECT employee_id FROM employees LIMIT 0");
    await pool.query("SELECT config_key FROM app_config LIMIT 0");
    await pool.query("SELECT id FROM attendance_records LIMIT 0");
    res.json({ status: "ok", database: "connected" });
  } catch (e) { console.error("health check failed", e.code || e.message); fail(res, 503, "数据库或数据表尚未就绪"); }
});
app.post("/api/session", loginRateLimit, async (req, res) => {
  const { employeeId, password } = req.body || {};
  if (typeof employeeId !== "string" || !employeeId || typeof password !== "string" || !password) return fail(res, 400, "工号和密码不能为空");
  try {
    const [rows] = await pool.execute("SELECT employee_id,display_name,enabled,must_change_password,password_hash,password_salt,role FROM employees WHERE employee_id=? LIMIT 1", [employeeId]);
    const employee = rows[0];
    if (!employee || !employee.enabled || !verifyPassword(employee, password)) return fail(res, 401, "工号或密码错误", "INVALID_CREDENTIALS");
    res.json({ token: issueToken(employee), employee: publicEmployee(employee) });
  } catch (e) { console.error("login failed", e.code || e.message); fail(res, 500, "登录服务暂时不可用"); }
});
app.use("/api", authenticated);
app.get("/api/config", async (_req, res) => {
  try {
    const [rows] = await pool.execute("SELECT config_value FROM app_config WHERE config_key='allowedSsid' LIMIT 1");
    res.json({ allowedSsid: rows[0]?.config_value || "" });
  } catch (e) { console.error("config read failed", e.code || e.message); fail(res, 500, "读取配置失败"); }
});
app.get("/api/attendance", async (req, res) => {
  const month = req.query.month;
  const day = req.query.day || chinaParts().day;
  if (month !== undefined) {
    if (typeof month !== "string" || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) return fail(res, 400, "月份格式无效");
    try {
      const [rows] = await pool.execute("SELECT id,employee_id,DATE_FORMAT(punched_at,'%Y-%m-%dT%H:%i:%s') AS at,punch_type,DATE_FORMAT(punch_day,'%Y-%m-%d') AS day FROM attendance_records WHERE employee_id=? AND punch_day >= ? AND punch_day < DATE_ADD(?, INTERVAL 1 MONTH) ORDER BY punched_at ASC", [req.session.employeeId, month + "-01", month + "-01"]);
      return res.json({ records: rows.map(r => ({ id: r.id, employeeId: String(r.employee_id), at: r.at, type: r.punch_type, day: r.day })) });
    } catch (e) { console.error("attendance query failed", e.code || e.message); return fail(res, 500, "读取考勤记录失败"); }
  }
  if (typeof day !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(day)) return fail(res, 400, "日期格式无效");
  try {
    const [rows] = await pool.execute("SELECT id,employee_id,DATE_FORMAT(punched_at,'%Y-%m-%dT%H:%i:%s') AS at,punch_type,DATE_FORMAT(punch_day,'%Y-%m-%d') AS day FROM attendance_records WHERE employee_id=? AND punch_day=? ORDER BY punched_at ASC", [req.session.employeeId, day]);
    res.json({ records: rows.map(r => ({ id: r.id, employeeId: String(r.employee_id), at: r.at, type: r.punch_type, day: r.day })) });
  } catch (e) { console.error("attendance query failed", e.code || e.message); fail(res, 500, "读取考勤记录失败"); }
});
app.post("/api/attendance", async (req, res) => {
  const { id, type } = req.body || {};
  if (typeof id !== "string" || !id || id.length > 128 || !["IN", "OUT", "TEMP_OUT"].includes(type)) return fail(res, 400, "打卡数据无效");
  const when = chinaParts();
  try {
    await pool.execute("INSERT INTO attendance_records (id,employee_id,punch_type,punch_day,punched_at) VALUES (?,?,?,?,?)", [id, req.session.employeeId, type, when.day, when.at.slice(0, 19).replace("T", " ")]);
    res.json({ record: { id, employeeId: req.session.employeeId, at: when.at, type, day: when.day } });
  } catch (e) {
    if (e.code === "ER_DUP_ENTRY") return fail(res, 409, "打卡记录编号重复");
    console.error("punch save failed", e.code || e.message); fail(res, 500, "打卡保存失败，请稍后重试");
  }
});
app.post("/api/admin/attendance/makeup", async (req, res) => {
  if (normalizeRole(req.session.role) !== "admin") return fail(res, 403, "仅管理员可以添加补班记录", "FORBIDDEN");
  const records = req.body?.records;
  if (!Array.isArray(records) || records.length < 2 || records.length > 100) return fail(res, 400, "补班记录格式无效");
  const seen = new Set();
  for (const r of records) {
    if (!r || typeof r.id !== "string" || !r.id || r.id.length > 128 || typeof r.employeeId !== "string" ||
        !/^\d{4}-\d{2}-\d{2}$/.test(r.day || "") || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(r.at || "") ||
        !["IN", "OUT"].includes(r.type) || seen.has(r.id)) return fail(res, 400, "补班记录内容无效");
    seen.add(r.id);
  }
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    for (const r of records) {
      const [employees] = await conn.execute("SELECT employee_id FROM employees WHERE employee_id=? AND enabled=1 LIMIT 1", [r.employeeId]);
      if (!employees.length) { await conn.rollback(); return fail(res, 400, "补班员工不存在或已停用"); }
      await conn.execute("INSERT INTO attendance_records (id,employee_id,punch_type,punch_day,punched_at) VALUES (?,?,?,?,?)", [r.id, r.employeeId, r.type, r.day, r.at.replace("T", " ")]);
    }
    await conn.commit();
    res.json({ ok: true, count: records.length });
  } catch (e) {
    await conn.rollback();
    if (e.code === "ER_DUP_ENTRY") return fail(res, 409, "补班记录编号重复，请重新提交");
    console.error("makeup save failed", e.code || e.message); fail(res, 500, "补班保存失败，请重试");
  } finally { conn.release(); }
});
app.post("/api/password", async (req, res) => {
  const { currentPassword, newPassword } = req.body || {};
  if (typeof currentPassword !== "string" || typeof newPassword !== "string" || newPassword.length < 6 || newPassword.length > 256) return fail(res, 400, "新密码至少 6 位，且不能超过 256 个字符");
  if (!verifyPassword(req.employee, currentPassword)) return fail(res, 401, "当前密码错误");
  const salt = crypto.randomBytes(16).toString("base64");
  const hash = passwordDigest(salt, newPassword).toString("base64");
  try {
    await pool.execute("UPDATE employees SET password_salt=?,password_hash=?,must_change_password=0 WHERE employee_id=?", [salt, hash, req.session.employeeId]);
    res.json({ ok: true });
  } catch (e) { console.error("password update failed", e.code || e.message); fail(res, 500, "密码保存失败，请重试"); }
});
app.use((_req, res) => fail(res, 404, "接口不存在", "NOT_FOUND"));
app.use((err, _req, res, _next) => { console.error("request error", err?.type || err?.name || "Error"); fail(res, 400, "请求格式无效", "INVALID_REQUEST"); });

const port = Number(process.env.PORT || 3000);
if (!TOKEN_SECRET) { console.error("缺少 TOKEN_SECRET，请在 .env 中配置随机密钥"); process.exit(1); }
const server = app.listen(port, "127.0.0.1", () => console.log(`WorkClockR2 MySQL API listening on 127.0.0.1:${port}`));
async function shutdown(signal) {
  console.log(`收到 ${signal}，正在安全关闭 API...`);
  server.close(async () => {
    try { await pool.end(); } catch (e) { console.error("关闭数据库连接池失败", e.code || e.message); }
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10000).unref();
}
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
