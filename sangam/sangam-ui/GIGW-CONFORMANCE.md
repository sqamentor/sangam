# GIGW / WCAG conformance matrix and assistive-technology audit plan (M-046)

Status: **draft for 0.1.0**, written 2026-11-12 by lane W2-C. Nothing here has been tested by an auditor yet.

## Scope and sources

- **Standard.** GIGW 3.0 adopts WCAG 2.1 level AA through IS 17802. This matrix lists the WCAG 2.1 A and AA success criteria with the UI code that addresses each one.
- **Annexure II numbering.** The mapping of each row to its GIGW Annexure II compliance-matrix number is left to the accessibility auditor (owner question Q-15). This file does not guess those numbers.
- **UI language.** The 0.1.0 UI is English-only (owner decision Q-06, register SL-147). Hindi and other Unicode **data** is still shown correctly, and `lang`/`dir` follow the data where the server provides them.

### Status values

| Status | Meaning |
|---|---|
| **Built** | Code exists, cited by file. Not yet verified by axe or by an assistive-technology user. |
| **Partly** | Some screens are covered, others are not. The gap is named. |
| **Open** | Not addressed. |
| **Audit** | Can only be judged by a person (content, cognitive load, AT behaviour). |

## Automated checks (axe-core)

- **Installed.** axe-core 4.14.0, exact-pinned dev dependency (download batch 2, approved CI-first; MPL-2.0, allowed by `deploy/licence/licence-policy.json:6`; no transitive dependencies). It is never shipped.
- **Check.** `src/test/axe.ts` runs WCAG 2.0/2.1 A and AA rules and fails on any `serious` or `critical` violation. `color-contrast` is off in jsdom (no layout); contrast is checked in the E2E run (M-048) and by the auditor.
- **Gate.** `src/a11y.test.tsx` covers the shared components and the W2-C screens. It runs in `npm test`, which `ui.yml` runs on every UI change, so no workflow change is needed. Other screens join as M-047 adds their component tests (W3-J), and the E2E journeys follow with M-048.

## Matrix (WCAG 2.1 A + AA)

| SC | Title | Status | Evidence / gap |
|---|---|---|---|
| 1.1.1 | Non-text content | Partly | The BPMN canvas has `role="img"` and an `aria-label` (`components/BpmnDiagram.tsx`), and the same figures are offered as a data table. Icons-only buttons have `aria-label` (`components/ui.tsx` Dialog close). Charts: check `components/Charts.tsx` with axe. |
| 1.2.x | Time-based media | N/A | The UI has no audio or video. |
| 1.3.1 | Info and relationships | Built | Tables have a `<caption>` and `<th scope="col">` (`components/ui.tsx` DataTable). Form fields have labels and `aria-describedby` for hints and errors (`components/ui.tsx` FieldWrap). |
| 1.3.2 | Meaningful sequence | Audit | DOM order follows the visual order (no CSS reordering). An auditor confirms. |
| 1.3.3 | Sensory characteristics | Audit | Status is shown as text badges, not colour alone (`components/ui.tsx` Badge). |
| 1.3.4 | Orientation | Built | Responsive layout, no orientation lock (`styles.css`). |
| 1.3.5 | Identify input purpose | Open | `autocomplete` tokens are missing on citizen-portal personal fields (`areas/portal/Portal.tsx`). |
| 1.4.1 | Use of colour | Partly | Badges carry text. The diagram heat-map uses colour plus the count overlay and the table. |
| 1.4.3 | Contrast (minimum) | Audit | Tokens are in `styles.css`. Needs a contrast run (axe) in light and dark. |
| 1.4.4 | Resize text | Built | rem-based type. An auditor confirms at 200%. |
| 1.4.5 | Images of text | Built | None used. |
| 1.4.10 | Reflow | Audit | Wide data tables scroll inside their container (`overflow-x: auto`). Check at 320 CSS px. |
| 1.4.11 | Non-text contrast | Audit | Focus ring and borders: contrast run. |
| 1.4.12 | Text spacing | Audit | |
| 1.4.13 | Content on hover or focus | Built | Tooltips use `title` only. There are no custom hover popups. |
| 2.1.1 | Keyboard | Partly | Every control is a native button, link or input. The BPMN canvas has keyboard zoom (`+`, `-`, `0`) and a table alternative. The bpmn-js **editor** palette is mouse-first (library limit, see M-117). |
| 2.1.2 | No keyboard trap | Built | Dialogs use native `<dialog>` with Esc and cancel (`components/ui.tsx` Dialog). |
| 2.1.4 | Character key shortcuts | Built | Shortcuts are active only while the canvas has focus. |
| 2.2.1 | Timing adjustable | Partly | Session expiry is handled by the IdP. The UI has no timed forms. The logout prompt is `app/LogoutPrompt.tsx`. |
| 2.2.2 | Pause, stop, hide | Built | Auto-refresh (bulk job, queue display) has no animation, and `prefers-reduced-motion` is honoured (`styles.css`). |
| 2.3.1 | Three flashes | Built | No flashing content. |
| 2.4.1 | Bypass blocks | Built | Skip link (`app/Layout.tsx`, `common.skipToContent`). |
| 2.4.2 | Page titled | Partly | Each screen has an `<h1>` (PageHeader). `document.title` is not updated per route: **Open**. |
| 2.4.3 | Focus order | Audit | |
| 2.4.4 | Link purpose | Built | Links carry their target text. |
| 2.4.5 | Multiple ways | Built | Role-aware navigation plus global search (`modules/platform.tsx` GlobalSearch). |
| 2.4.6 | Headings and labels | Built | |
| 2.4.7 | Focus visible | Built | `:focus-visible` outline (`styles.css`). |
| 2.5.1–2.5.4 | Pointer | Built | No path gestures, and no drag interactions in the app's own screens (the bpmn-js editor canvas is the library's). |
| 3.1.1 | Language of page | Built | `document.documentElement.lang` is set (`i18n/index.ts`). |
| 3.1.2 | Language of parts | Partly | Notices render with the server's language and direction (`modules/compliance.tsx` NoticeView). Data cells and values use `dir="auto"` (`components/ui.tsx`). Other mixed-language data (Devanagari names in an English UI) carries no `lang` because the API does not say which language a value is in. |
| 3.2.1 / 3.2.2 | On focus / on input | Built | No context change on focus or input. Every submit is an explicit button. |
| 3.2.3 / 3.2.4 | Consistent navigation and identification | Built | One shell (`app/Layout.tsx`), one component set (`components/ui.tsx`). |
| 3.3.1 | Error identification | Built | `aria-invalid` plus inline errors. Server problems are shown in a `role="alert"` box (`components/ui.tsx` ProblemAlert). |
| 3.3.2 | Labels or instructions | Built | Hints on every declarative field (`modules/engine.tsx`, `fld.*Hint`). |
| 3.3.3 | Error suggestion | Partly | Refusals explained in words: bulk 413, pack lifecycle, promotions (W2-C). Other modules show the server's `detail`. |
| 3.3.4 | Error prevention (legal, financial) | Built | Reason dialogs, previews and four-eyes approval on consequential actions (`components/ui.tsx` ReasonDialog, PreviewList). |
| 4.1.1 | Parsing | Built | React output. (This criterion is obsolete in WCAG 2.2.) |
| 4.1.2 | Name, role, value | Partly | Native elements. Custom tabs use ARIA tab roles (`components/ui.tsx` Tabs). bpmn-js / dmn-js / form-js internals: auditor. |
| 4.1.3 | Status messages | Built | Polite live region and `announce()` (`app/Layout.tsx`, `lib/hooks.ts`). |

## Known gaps to fix (tracked in M-046)

1. `document.title` per route (2.4.2).
2. `autocomplete` tokens on citizen personal-data fields (1.3.5).
3. Keyboard use of the bpmn-js editor palette (2.1.1). Re-check after the vite 8 toolchain move (M-117).
4. axe-core on the remaining screens (with their M-047 component tests) and in the E2E journeys (M-048).

## Assistive-technology audit plan (owner question Q-15: the auditor is still to be named)

- **Who.** One department nominee who uses assistive technology daily, plus an external auditor familiar with GIGW / IS 17802.
- **What, desktop.** NVDA (latest) with Firefox and Chrome on Windows; JAWS (if licensed) with Chrome.
- **What, mobile.** TalkBack with Chrome on Android, for the citizen portal and the field PWA.
- **Also covered.** Keyboard only, 200% zoom, 320 px reflow, Windows high-contrast (forced colours).
- **Journeys.** Sign-in and seat choice. Inbox, then task detail, then approve / return with a reason. Start a service. The citizen portal applies, tracks and replies to a clarification. The privacy notice (public). A grievance. Admin: a bulk ingest upload. Modeller: open a package and read the diagram table.
- **Content.** English UI, with Hindi and Urdu sample data, to check that names and notices are read in the right language and direction.
- **Output.** Per journey, a findings list (SC, severity, steps, AT/browser). Each finding becomes a backlog row. The auditor fills in the Annexure II numbers in this matrix.
- **Exit.** No open critical or serious finding on the citizen-facing journeys before a State-facing pilot.
