# Example UI plugin

A signed plugin jar carries `ui/manifest.json` and one JavaScript file. The server verifies the signature when it loads the jar and serves the file at `GET /api/v1/plugins/ui/{id}/assets/{path}`. `examples/plugin-connector-example` is a full server-side example.

```json
{ "id": "example.hello", "version": "1.0.0", "name": "Hello", "entry": "plugin.js",
  "contributes": [{ "slot": "admin.panel", "title": "Hello", "titleHi": "नमस्ते" }] }
```

The module either exports `mount(element, ctx)`, placed at each manifest slot, or a default `{ contributions: [...] }` as `plugin.js` here does. A file can use either form; `src/plugins/host.tsx` defines the context.

**Points:** `app.page`, `app.api`, `dashboard.widget`, `process-definition.tab`, `process-instance.tab`, `process-instance.action`, `task.tab`, `decision.tab`, `admin.page` (alias `admin.panel`), `diagram.overlay`.

**No secrets in plugin code.** Entry files and assets are served without a token (the manifest list stays authenticated), so anyone who can reach the server can read them. Call the API from `ctx.api` at run time instead.

The shell imports the entry with a same-origin dynamic import under the strict `script-src 'self'` policy, so relative imports within the bundle work.
