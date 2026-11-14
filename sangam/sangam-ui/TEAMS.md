<!-- SPDX-License-Identifier: Apache-2.0 -->
# Sangam app for Microsoft Teams (optional module)

Owner decision 162 (M-109). The Teams app is a personal tab, "My work", that shows the signed-in user's Sangam task
inbox and task detail. It is optional. If it is not installed, or `teams.enabled` is false, every other part of Sangam
works exactly the same.

## How sign-in works

The tab signs in through a Teams pop-up that runs the UI's own OIDC login: authorisation code with PKCE through
oidc-client-ts, against the same issuer, client and scopes as the console. Any IdP works, including an air-gapped
Keycloak. Microsoft identities and Teams SSO are not used.

1. The tab calls `authentication.authenticate` with `teams.html?view=auth-start`.
2. The pop-up redirects to the IdP. The redirect URI is `https://<ui-host><base>teams.html`.
3. The IdP sends the browser back to that page. The pop-up exchanges the code, then passes the signed-in session to
   the tab with `authentication.notifySuccess`. Storage cannot be used for this hand-over, because the tab is a
   third-party frame whose storage is partitioned away from the pop-up.
4. The tab accepts the session only if it holds an access token that has not expired. It keeps the session in its
   own sessionStorage, as the console does. Every API call carries the user's own bearer token, and the server
   authorises it as usual.

"Sign out of this tab" removes only the tokens held in the tab. The IdP sign-out page cannot run inside a Teams frame.

## Code layout

| Part | Where |
|---|---|
| Page | `teams.html`, a second Vite input, built only when the file is present |
| Entry point | `src/teams/main.tsx` |
| Sign-in logic | `src/teams/teamsAuth.ts`. It does not use the Teams SDK, so it can be tested |
| Tab UI | `src/teams/TeamsApp.tsx`, which reuses TaskInbox and TaskDetail |
| Teams SDK | `src/teams/sdk.ts`. This is the only importer of `@microsoft/teams-js` 2.59.0 |

The console never imports `src/teams`. `src/teams/teams.test.tsx` checks this.

## Dependency

`@microsoft/teams-js` 2.59.0 and its runtime closure (debug 4.4.3, ms 2.1.3 and base64-js 1.5.1) are all MIT. They
were installed with `--ignore-scripts` from the tarballs recorded in `sangam-handoff/DOWNLOADS-WAVE4.md` (D-3), and
`package-lock.json` pins their integrity.

## Installing for one site

1. **IdP.** Add `https://<ui-host><base>teams.html` as a redirect URI of the UI client.
2. **UI server, `/config.json`.** Set `"teams": {"enabled": true}`.
3. **UI server, `/teams.html` response headers.** These differ from the rest of the UI:
   - Content-Security-Policy `frame-ancestors` lists the Microsoft Teams hosts instead of `'none'`. Owner decision 213
     makes this list a deploy setting. Its default is Microsoft's published Teams host list, which the deploy owner
     looks up and records with its source and date. The setting applies only to `/teams.html`; every other page keeps
     `frame-ancestors 'none'`. Change the setting rather than the image when Microsoft changes its list.
   - No `X-Frame-Options: DENY`.
   - No `Cross-Origin-Opener-Policy: same-origin`. The pop-up needs its opener to reach Teams.
   - `connect-src` and `form-action` include the OIDC issuer, as on every page.

   The page has no meta CSP, so the header is the only policy. The console's `index.html` has none either (owner
   decision 214).
4. **Package.** Build it with:

   ```
   npm run teams:package -- --app-id <new UUID> --ui-host <ui-host> --idp-host <idp-host> \
     --developer-name "<organisation>" --website-url https://... --privacy-url https://... --terms-url https://... \
     [--ui-base /]
   ```

   This writes `dist-teams/sangam-teams.zip`, a manifest built from `teams/manifest.template.json` plus generated
   icons. The build is offline and reproducible.
5. **Teams.** Upload the zip in the Teams admin centre.
