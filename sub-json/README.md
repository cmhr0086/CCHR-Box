# sub-json

Cloudflare Worker backend for CCHR-Box invite-code subscription lookup.

## API

```http
POST /
Content-Type: application/json

{ "inviteCode": "邀请码" }
```

Success:

```json
{ "subscriptionUrl": "https://example.com/subscription" }
```

## D1 operations

The web admin panel is available at:

```text
https://sub-json.1630086.xyz/admin
```

Set or rotate the admin password:

```bash
npx wrangler secret put ADMIN_PASSWORD
```

The admin panel manages the same fields as the D1 table: invite code, subscription URL, enabled state, and note.

Insert or update an invite code:

```bash
npx wrangler d1 execute sub-json-db --remote --command "INSERT INTO invite_subscriptions (invite_code, subscription_url, enabled, note) VALUES ('测试码', 'https://example.com/sub', 1, 'demo') ON CONFLICT(invite_code) DO UPDATE SET subscription_url = excluded.subscription_url, enabled = excluded.enabled, note = excluded.note;"
```

Disable an invite code:

```bash
npx wrangler d1 execute sub-json-db --remote --command "UPDATE invite_subscriptions SET enabled = 0 WHERE invite_code = '测试码';"
```

## Deployment

```bash
npm install
npx wrangler types
npm test
npx wrangler d1 migrations apply sub-json-db --remote
npx wrangler deploy
```
