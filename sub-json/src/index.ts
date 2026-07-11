type AppEnv = Env & {
  ADMIN_PASSWORD?: string;
};

type InviteRow = {
  invite_code: string;
  subscription_url: string;
  enabled: number;
  note: string | null;
  created_at: string;
  updated_at: string;
};

type LookupRow = {
  subscription_url: string;
};

type AnnouncementRow = {
  id: string;
  title: string | null;
  content: string | null;
  enabled: number;
  updated_at: string;
};

type ErrorStatus = 400 | 404 | 405 | 415 | 500;
type AdminMessage = { type: "ok" | "error"; text: string } | null;

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

const HTML_HEADERS = {
  "content-type": "text/html; charset=utf-8",
  "cache-control": "no-store",
};

const MAX_JSON_BYTES = 4096;
const MAX_FORM_BYTES = 65536;
const SESSION_COOKIE = "sub_json_admin";
const SESSION_MAX_AGE_SECONDS = 60 * 60 * 24 * 7;

export default {
  async fetch(request: Request, env: AppEnv): Promise<Response> {
    try {
      return await handleRequest(request, env);
    } catch (error) {
      console.error(
        JSON.stringify({
          event: "unhandled_error",
          message: error instanceof Error ? error.message : String(error),
        }),
      );
      if (new URL(request.url).pathname.startsWith("/admin")) {
        return html(renderPage("服务暂时不可用", "<p>服务暂时不可用</p>"), { status: 500 });
      }
      return jsonError(500, "服务暂时不可用");
    }
  },
} satisfies ExportedHandler<AppEnv>;

async function handleRequest(request: Request, env: AppEnv): Promise<Response> {
  const url = new URL(request.url);

  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: JSON_HEADERS });
  }

  if (url.pathname.startsWith("/admin")) {
    return handleAdminRequest(request, env, url);
  }

  if (url.pathname === "/health" && request.method === "GET") {
    return json({ ok: true, service: "sub-json" });
  }

  if (url.pathname === "/announcement") {
    if (request.method !== "GET") {
      return jsonError(405, "请求方法不支持", { Allow: "GET" });
    }
    return handleAnnouncementRequest(env);
  }

  if (url.pathname !== "/") {
    return jsonError(404, "接口不存在");
  }

  if (request.method !== "POST") {
    return jsonError(405, "请求方法不支持", { Allow: "POST" });
  }

  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) {
    return jsonError(415, "仅支持 JSON 请求");
  }

  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (contentLength > MAX_JSON_BYTES) {
    return jsonError(400, "请求内容过大");
  }

  const body = await parseJsonBody(request);
  if (!body.ok) {
    return jsonError(400, "JSON 格式错误");
  }

  const inviteCode = readInviteCode(body.value);
  if (inviteCode === null) {
    return jsonError(400, "请输入邀请码");
  }

  const row = await env.DB.prepare(
    "SELECT subscription_url FROM invite_subscriptions WHERE invite_code = ? AND enabled = 1 LIMIT 1",
  )
    .bind(inviteCode)
    .first<LookupRow>();

  if (row === null) {
    return jsonError(404, "邀请码无效或已停用");
  }

  return json({ subscriptionUrl: row.subscription_url });
}

async function handleAdminRequest(
  request: Request,
  env: AppEnv,
  url: URL,
): Promise<Response> {
  if (!env.ADMIN_PASSWORD) {
    return html(
      renderPage(
        "后台未配置",
        "<section class=\"panel\"><h1>后台未配置</h1><p>请先设置 Cloudflare Secret：<code>ADMIN_PASSWORD</code></p></section>",
      ),
      { status: 503 },
    );
  }

  if (url.pathname === "/admin/login" && request.method === "POST") {
    return handleAdminLogin(request, env);
  }

  const isAuthed = await isAdminAuthenticated(request, env);
  if (!isAuthed) {
    if (url.pathname !== "/admin" || request.method !== "GET") {
      return redirect("/admin");
    }
    return html(renderLoginPage(messageFromUrl(url)));
  }

  if (url.pathname === "/admin" && request.method === "GET") {
    return renderAdminHome(env, messageFromUrl(url));
  }

  if (url.pathname === "/admin/logout" && request.method === "POST") {
    return redirect("/admin", {
      "Set-Cookie": `${SESSION_COOKIE}=; HttpOnly; Secure; SameSite=Strict; Path=/admin; Max-Age=0`,
    });
  }

  if (url.pathname === "/admin/invites" && request.method === "POST") {
    return handleInviteCreate(request, env);
  }

  if (url.pathname === "/admin/announcement" && request.method === "POST") {
    return handleAnnouncementUpdate(request, env);
  }

  const inviteMatch = /^\/admin\/invites\/([^/]+)(\/delete)?$/.exec(url.pathname);
  if (inviteMatch && request.method === "POST") {
    const inviteCode = decodeURIComponent(inviteMatch[1]);
    if (inviteMatch[2] === "/delete") {
      return handleInviteDelete(env, inviteCode);
    }
    return handleInviteUpdate(request, env, inviteCode);
  }

  return html(renderPage("接口不存在", "<p>后台接口不存在</p>"), { status: 404 });
}

async function handleAdminLogin(request: Request, env: AppEnv): Promise<Response> {
  const form = await readForm(request);
  if (!form.ok) {
    return html(renderLoginPage({ type: "error", text: form.message }), { status: 400 });
  }

  const password = form.data.get("password")?.toString() ?? "";
  if (!(await secureTextEqual(password, env.ADMIN_PASSWORD ?? ""))) {
    return html(renderLoginPage({ type: "error", text: "密码错误" }), { status: 401 });
  }

  const cookie = await createSessionCookie(env);
  return redirect("/admin?message=登录成功", { "Set-Cookie": cookie });
}

async function renderAdminHome(env: AppEnv, message: AdminMessage): Promise<Response> {
  const rows = await env.DB.prepare(
    "SELECT invite_code, subscription_url, enabled, note, created_at, updated_at FROM invite_subscriptions ORDER BY created_at DESC",
  ).all<InviteRow>();
  const announcement = await getAnnouncement(env);

  const body = `
    <section class="toolbar">
      <div>
        <h1>sub-json 后台</h1>
        <p>管理邀请码与订阅链接</p>
      </div>
      <form method="post" action="/admin/logout">
        <button type="submit" class="secondary">退出</button>
      </form>
    </section>
    ${renderMessage(message)}
    ${renderAnnouncementForm(announcement)}
    <section class="panel">
      <h2>新增或覆盖邀请码</h2>
      <form method="post" action="/admin/invites" class="grid-form">
        <label>邀请码<input name="inviteCode" required autocomplete="off"></label>
        <label>订阅链接<input name="subscriptionUrl" required type="url" placeholder="https://..."></label>
        <label>备注<input name="note"></label>
        <label class="checkbox"><input name="enabled" type="checkbox" value="1" checked> 启用</label>
        <button type="submit">保存</button>
      </form>
    </section>
    <section class="panel">
      <h2>邀请码列表</h2>
      ${renderInviteTable(rows.results ?? [])}
    </section>
  `;

  return html(renderPage("sub-json 后台", body));
}

async function handleAnnouncementRequest(env: AppEnv): Promise<Response> {
  const announcement = await getAnnouncement(env);
  const content = announcement?.content?.trim() ?? "";
  if (announcement === null || announcement.enabled !== 1 || content.length === 0) {
    return json({ enabled: false });
  }
  return json({
    enabled: true,
    title: announcement.title?.trim() ?? "",
    content,
    updatedAt: announcement.updated_at,
  });
}

async function getAnnouncement(env: AppEnv): Promise<AnnouncementRow | null> {
  return env.DB.prepare(
    "SELECT id, title, content, enabled, updated_at FROM app_announcements WHERE id = ? LIMIT 1",
  )
    .bind("default")
    .first<AnnouncementRow>();
}

async function handleAnnouncementUpdate(request: Request, env: AppEnv): Promise<Response> {
  const form = await readForm(request);
  if (!form.ok) return redirect(`/admin?error=${encodeURIComponent(form.message)}`);

  const title = (form.data.get("title")?.toString() ?? "").trim();
  const content = (form.data.get("content")?.toString() ?? "").trim();
  const enabled = form.data.get("enabled") === "1" ? 1 : 0;

  await env.DB.prepare(
    `INSERT INTO app_announcements (id, title, content, enabled)
     VALUES ('default', ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       title = excluded.title,
       content = excluded.content,
       enabled = excluded.enabled`,
  )
    .bind(title, content, enabled)
    .run();

  return redirect("/admin?message=公告已保存");
}

async function handleInviteCreate(request: Request, env: AppEnv): Promise<Response> {
  const form = await readForm(request);
  if (!form.ok) return redirect(`/admin?error=${encodeURIComponent(form.message)}`);

  const parsed = parseInviteForm(form.data);
  if (!parsed.ok) return redirect(`/admin?error=${encodeURIComponent(parsed.message)}`);

  await env.DB.prepare(
    `INSERT INTO invite_subscriptions (invite_code, subscription_url, enabled, note)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(invite_code) DO UPDATE SET
       subscription_url = excluded.subscription_url,
       enabled = excluded.enabled,
       note = excluded.note`,
  )
    .bind(parsed.inviteCode, parsed.subscriptionUrl, parsed.enabled, parsed.note)
    .run();

  return redirect("/admin?message=邀请码已保存");
}

async function handleInviteUpdate(
  request: Request,
  env: AppEnv,
  inviteCode: string,
): Promise<Response> {
  const form = await readForm(request);
  if (!form.ok) return redirect(`/admin?error=${encodeURIComponent(form.message)}`);

  const parsed = parseInviteForm(form.data, inviteCode);
  if (!parsed.ok) return redirect(`/admin?error=${encodeURIComponent(parsed.message)}`);

  await env.DB.prepare(
    "UPDATE invite_subscriptions SET subscription_url = ?, enabled = ?, note = ? WHERE invite_code = ?",
  )
    .bind(parsed.subscriptionUrl, parsed.enabled, parsed.note, inviteCode)
    .run();

  return redirect("/admin?message=邀请码已更新");
}

async function handleInviteDelete(env: AppEnv, inviteCode: string): Promise<Response> {
  await env.DB.prepare("DELETE FROM invite_subscriptions WHERE invite_code = ?")
    .bind(inviteCode)
    .run();
  return redirect("/admin?message=邀请码已删除");
}

function renderInviteTable(rows: InviteRow[]): string {
  if (rows.length === 0) {
    return "<p class=\"empty\">暂无邀请码</p>";
  }

  return `
    <div class="table">
      ${rows
        .map((row) => {
          const inviteCode = escapeHtml(row.invite_code);
          const actionCode = encodeURIComponent(row.invite_code);
          const checked = row.enabled === 1 ? " checked" : "";
          return `
            <form method="post" action="/admin/invites/${actionCode}" class="row">
              <div class="code">${inviteCode}</div>
              <label>订阅链接<input name="subscriptionUrl" required type="url" value="${escapeHtml(row.subscription_url)}"></label>
              <label>备注<input name="note" value="${escapeHtml(row.note ?? "")}"></label>
              <label class="checkbox"><input name="enabled" type="checkbox" value="1"${checked}> 启用</label>
              <div class="meta">创建 ${escapeHtml(row.created_at)}<br>更新 ${escapeHtml(row.updated_at)}</div>
              <button type="submit">更新</button>
              <button type="submit" class="danger" formmethod="post" formaction="/admin/invites/${actionCode}/delete" onclick="return confirm('确定删除邀请码 ${inviteCode}？')">删除</button>
            </form>
          `;
        })
        .join("")}
    </div>
  `;
}

function renderAnnouncementForm(announcement: AnnouncementRow | null): string {
  const checked = announcement?.enabled === 1 ? " checked" : "";
  return `
    <section class="panel">
      <h2>公告</h2>
      <form method="post" action="/admin/announcement" class="announcement-form">
        <label>标题<input name="title" value="${escapeHtml(announcement?.title ?? "")}" placeholder="公告"></label>
        <label>内容<textarea name="content" rows="5" placeholder="填写后会显示在客户端首页">${escapeHtml(announcement?.content ?? "")}</textarea></label>
        <label class="checkbox"><input name="enabled" type="checkbox" value="1"${checked}> 启用公告</label>
        <button type="submit">保存公告</button>
      </form>
    </section>
  `;
}

function renderLoginPage(message: AdminMessage): string {
  return renderPage(
    "登录",
    `
      <section class="login panel">
        <h1>sub-json 后台</h1>
        ${renderMessage(message)}
        <form method="post" action="/admin/login">
          <label>管理员密码<input name="password" type="password" required autofocus></label>
          <button type="submit">登录</button>
        </form>
      </section>
    `,
  );
}

function renderPage(title: string, body: string): string {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    :root { color-scheme: dark; --bg: #0b0f12; --panel: #151b1f; --line: #2d363c; --text: #e7edf0; --muted: #94a3ab; --accent: #607d8b; --danger: #ef5350; }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: var(--bg); color: var(--text); }
    main { width: min(1180px, calc(100% - 32px)); margin: 32px auto; }
    h1, h2, p { margin: 0; }
    h1 { font-size: 24px; }
    h2 { font-size: 18px; margin-bottom: 16px; }
    p { color: var(--muted); margin-top: 6px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 18px; margin-bottom: 16px; }
    .login { max-width: 420px; margin: 15vh auto 0; }
    label { display: grid; gap: 6px; color: var(--muted); font-size: 13px; }
    input, textarea { width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 10px 11px; background: #0f1417; color: var(--text); font: inherit; }
    textarea { resize: vertical; min-height: 120px; }
    input:focus, textarea:focus { outline: 2px solid color-mix(in srgb, var(--accent), transparent 45%); border-color: var(--accent); }
    button { border: 0; border-radius: 6px; padding: 10px 14px; color: white; background: var(--accent); font: inherit; cursor: pointer; white-space: nowrap; }
    button.secondary { background: #37474f; }
    button.danger { background: var(--danger); }
    .grid-form { display: grid; grid-template-columns: 1fr 2fr 1fr auto auto; gap: 12px; align-items: end; }
    .announcement-form { display: grid; gap: 12px; }
    .checkbox { display: flex; align-items: center; gap: 8px; min-height: 41px; }
    .checkbox input { width: auto; }
    .message { border-radius: 6px; padding: 11px 12px; margin-bottom: 16px; background: #143623; color: #b9f6ca; }
    .message.error { background: #421b1b; color: #ffcdd2; }
    .empty { padding: 12px 0; }
    .table { display: grid; gap: 10px; }
    .row { display: grid; grid-template-columns: 170px minmax(220px, 2fr) minmax(160px, 1fr) auto 180px auto auto; gap: 10px; align-items: end; padding: 12px; border: 1px solid var(--line); border-radius: 8px; }
    .code { align-self: center; font-weight: 700; overflow-wrap: anywhere; }
    .meta { color: var(--muted); font-size: 12px; line-height: 1.5; align-self: center; }
    code { background: #0f1417; border: 1px solid var(--line); border-radius: 4px; padding: 2px 5px; }
    @media (max-width: 920px) {
      main { width: min(100% - 20px, 680px); margin: 18px auto; }
      .toolbar { align-items: flex-start; }
      .grid-form, .row { grid-template-columns: 1fr; }
      button { width: 100%; }
    }
  </style>
</head>
<body><main>${body}</main></body>
</html>`;
}

async function readForm(
  request: Request,
): Promise<{ ok: true; data: FormData } | { ok: false; message: string }> {
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (contentLength > MAX_FORM_BYTES) {
    return { ok: false, message: "表单内容过大" };
  }
  try {
    return { ok: true, data: await request.formData() };
  } catch {
    return { ok: false, message: "表单格式错误" };
  }
}

function parseInviteForm(
  form: FormData,
  fixedInviteCode?: string,
):
  | { ok: true; inviteCode: string; subscriptionUrl: string; enabled: number; note: string }
  | { ok: false; message: string } {
  const inviteCode = (fixedInviteCode ?? form.get("inviteCode")?.toString() ?? "").trim();
  const subscriptionUrl = (form.get("subscriptionUrl")?.toString() ?? "").trim();
  const note = (form.get("note")?.toString() ?? "").trim();
  const enabled = form.get("enabled") === "1" ? 1 : 0;

  if (inviteCode.length === 0) {
    return { ok: false, message: "邀请码不能为空" };
  }
  if (!isHttpUrl(subscriptionUrl)) {
    return { ok: false, message: "订阅链接必须是 http:// 或 https:// 地址" };
  }
  return { ok: true, inviteCode, subscriptionUrl, enabled, note };
}

function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

async function isAdminAuthenticated(request: Request, env: AppEnv): Promise<boolean> {
  const session = readCookie(request.headers.get("cookie") ?? "", SESSION_COOKIE);
  if (!session) return false;
  const [issuedAtText, signature] = session.split(".");
  const issuedAt = Number(issuedAtText);
  if (!Number.isSafeInteger(issuedAt) || !signature) return false;
  const now = Math.floor(Date.now() / 1000);
  if (issuedAt > now || now - issuedAt > SESSION_MAX_AGE_SECONDS) return false;
  const expected = await signSession(issuedAtText, env.ADMIN_PASSWORD ?? "");
  return secureTextEqual(signature, expected);
}

async function createSessionCookie(env: AppEnv): Promise<string> {
  const issuedAt = Math.floor(Date.now() / 1000).toString();
  const signature = await signSession(issuedAt, env.ADMIN_PASSWORD ?? "");
  return `${SESSION_COOKIE}=${issuedAt}.${signature}; HttpOnly; Secure; SameSite=Strict; Path=/admin; Max-Age=${SESSION_MAX_AGE_SECONDS}`;
}

async function signSession(issuedAt: string, password: string): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(password),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(`sub-json:${issuedAt}`));
  return base64UrlEncode(new Uint8Array(signature));
}

async function secureTextEqual(left: string, right: string): Promise<boolean> {
  const [leftHash, rightHash] = await Promise.all([sha256(left), sha256(right)]);
  return constantTimeBytesEqual(leftHash, rightHash);
}

async function sha256(value: string): Promise<Uint8Array> {
  const bytes = new TextEncoder().encode(value);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

function constantTimeBytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  let diff = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    diff |= (left[index] ?? 0) ^ (right[index] ?? 0);
  }
  return diff === 0;
}

function readCookie(cookieHeader: string, name: string): string | null {
  for (const cookie of cookieHeader.split(";")) {
    const [rawName, ...rawValue] = cookie.trim().split("=");
    if (rawName === name) {
      return rawValue.join("=");
    }
  }
  return null;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function parseJsonBody(
  request: Request,
): Promise<{ ok: true; value: unknown } | { ok: false }> {
  try {
    return { ok: true, value: await request.json() };
  } catch {
    return { ok: false };
  }
}

function readInviteCode(value: unknown): string | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return null;
  }
  const inviteCode = (value as Record<string, unknown>).inviteCode;
  if (typeof inviteCode !== "string") {
    return null;
  }
  const normalized = inviteCode.trim();
  return normalized.length > 0 ? normalized : null;
}

function messageFromUrl(url: URL): AdminMessage {
  const error = url.searchParams.get("error");
  if (error) return { type: "error", text: error };
  const message = url.searchParams.get("message");
  if (message) return { type: "ok", text: message };
  return null;
}

function renderMessage(message: AdminMessage): string {
  if (message === null) return "";
  const className = message.type === "error" ? "message error" : "message";
  return `<div class="${className}">${escapeHtml(message.text)}</div>`;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function redirect(location: string, headers?: HeadersInit): Response {
  return new Response(null, {
    status: 303,
    headers: {
      Location: location,
      ...headers,
    },
  });
}

function html(markup: string, init?: ResponseInit): Response {
  return new Response(markup, {
    ...init,
    headers: {
      ...HTML_HEADERS,
      ...init?.headers,
    },
  });
}

function json(data: unknown, init?: ResponseInit): Response {
  return new Response(JSON.stringify(data), {
    ...init,
    headers: {
      ...JSON_HEADERS,
      ...init?.headers,
    },
  });
}

function jsonError(
  status: ErrorStatus,
  message: string,
  headers?: HeadersInit,
): Response {
  return json({ message }, { status, headers });
}
