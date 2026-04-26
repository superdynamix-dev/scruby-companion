# Scruby Companion Plugin

## Project

Hytale server plugin (Java 25, Gradle) implementing a companion system. Package: `org.example.plugin`.

## CRITICAL: Deploy Flow (DO NOT MODIFY)

These paths and this flow are FIXED and must NEVER be changed by any agent.

**Paths:**
- **Plugin source:** `C:\Development\Scruby-Companion`
- **Server directory:** `%USERPROFILE%\Desktop\Hytale-Dedicated-DevServer\Server`
- **Mods directory:** `%USERPROFILE%\Desktop\Hytale-Dedicated-DevServer\Server\mods`
- **Connect:** `127.0.0.1:5520`

**Deploy command (from project root OR server directory):**
```powershell
powershell -File dev-cycle.ps1 -PluginDir "C:\Development\Scruby-Companion"
```

**What it does (4 phases, in order):**
1. Builds plugin with JDK 17 via Gradle
2. Kills running HytaleServer process
3. Copies JAR to `Server/mods/`
4. Starts new server with JDK 25

**Rules:**
- Only bump the version in `gradle.properties` when the user explicitly requests an upload or release
- NEVER start the server automatically — always ask the user first (EXCEPT during bugfix sessions where user has approved auto-deploy)
- NEVER change the `$PluginDir` default in `dev-cycle.ps1`
- NEVER change server paths, port, or JDK versions
- Always use `-PluginDir` parameter pointing to THIS repository when calling from outside the project root

## CRITICAL: HyUI UI Work (DO NOT SKIP)

All UI in this plugin is built with **HyUI (HYUIML)** — an HTML-inspired syntax that maps to Hytale's native UI system. **HyUI is NOT a browser.** Classic HTML/CSS assumptions will silently break or get stripped.

### Rule 1 — Read the docs BEFORE touching UI code or mockups

Before any HUD/menu/mockup work, read:
- `docs/hyui-doku/hyuiml-limitations.md` (what does NOT work)
- `docs/hyui-doku/hyuiml-css.md` (supported CSS properties)
- `docs/hyui-doku/hyuiml-elements.md` (when using a new element type)

Not "maybe skim" — **read**. This is a hard gate.

### Rule 2 — Mockups MUST use only HyUI-renderable primitives

No more "looks great in the browser, impossible in-game." If you build an HTML mockup to propose a design, restrict CSS to what HyUI actually renders (see Rule 3 below). A mockup that promises effects HyUI cannot deliver is a broken mockup.

### Rule 3 — Forbidden in mockup AND code

| Forbidden | Replace with |
|---|---|
| `box-shadow` | nothing — omit |
| `transform` (translate/rotate/scale) | nothing — omit |
| `transition` / `animation` | nothing — `:hover` switches instantly |
| `border-radius` | nothing — everything is rectangular |
| `position: absolute / relative / fixed / sticky` | `anchor-*` + `layout-mode` |
| `z-index` | DOM order |
| `opacity` | use `rgba(r,g,b,a)` directly in the color |
| standard `border: 1px solid X` | `background-color: rgba(...) <borderSize>` idiom |
| CSS units (`px`, `rem`, `%`, `em`) | raw numbers (e.g. `font-size: 14`) |
| `display: grid`, `gap`, `flex-wrap`, `flex-grow`, `flex-shrink`, `order`, `align-self` | `layout-mode` + `flex-weight` + nested `<div>` |
| pseudo-elements (`::before`, `::after`) | a separate `<div>` / `<p>` element |
| pseudo-classes except `:hover` (`:active`, `:focus`, `:nth-child`) | none — not supported |

### Rule 4 — Allowed primitives

- **Layout:** `layout-mode` (Top/Left/MiddleCenter/...), `flex-weight`, `anchor-width/height/left/right/top/bottom/horizontal/vertical/full`, `padding`
- **Text:** `color`, `font-size` (number, no unit), `font-weight`, `font-style`, `text-align`, `vertical-align`, `letter-spacing`, `white-space`
- **Colors:** `#RRGGBB` or `rgba(r,g,b,a)` (auto-converted)
- **Border idiom:** `background-color: rgba(r,g,b,a) <size>` — never `border:`
- **Interaction:** `:hover` only
- **Style refs:** `@Name { ... }` blocks for reusable label/background styles on `custom-textbutton`
- **Elements:** `<div>`, `<p>`, `<button>`, `<input>`, `<nav class="tabs">`, `<img class="dynamic-image">`

### Rule 5 — Mockup presentation

Every mockup must end with a **"HyUI-renderable: YES / NO / PARTIAL"** block. For PARTIAL, list exactly which effects will be dropped in the HyUI translation (so the user decides on a realistic basis, not a browser illusion).

### Rule 6 — Color-only tweaks inside existing constructs

If the change only swaps values inside an already-working HyUI construct (e.g. replacing a green `rgba()` with a gold `rgba()` in an existing `background-color: rgba(...) 1` line), Rules 1–3 don't need to be re-validated. They apply to **new** constructs.

### Rule 7 — Pre-flight doc-citation is mandatory before ANY HyUI edit

Before issuing any Edit/Write call that changes a HYUIML attribute (`layout-mode`, `anchor-*`, `padding`, `margin-*`, `background-color`, `background-image`, `vertical-align`, `horizontal-align`, `text-align`, `flex-weight`, `display`, `visibility`, `color`, `font-*`, or any `data-hyui-*`), the user-facing message immediately preceding that edit MUST contain a **"Doc check"** block with:

- The HyUI doc file(s) consulted (e.g. `docs/hyui-doku/hyuiml-css.md`)
- A short quote or section reference supporting the chosen approach
- If the property/behavior is **not in the docs**: say so explicitly, state the assumption being made, and flag the risk before proceeding

A missing Doc-check block on a HyUI-touching edit is a rule violation. This applies even to small tweaks that feel "obvious" — the whole point is to catch guesses.

Color-only value swaps covered by Rule 6 are exempt (same construct, no attribute change).

### Rule 8 — Docs-first Read at the start of every UI turn

When the user's message requests a UI change (color, layout, size, padding, position, border, hover-state, animation, alignment, spacing, typography, or any visual adjustment), the **first tool call** in that turn must be a `Read` on one of:

- `docs/hyui-doku/hyuiml-limitations.md`
- `docs/hyui-doku/hyuiml-css.md`
- `docs/hyui-doku/hyuiml-elements.md`

Any Edit/Write tool call to HyUI-rendering source files (e.g. `ScrubySkillTreePage.java`, `ScrubyHudManager.java`, `*.ui`) BEFORE this initial doc-Read is forbidden.

Rule 6's color-only exemption still applies: if the whole turn is swapping a color value inside an existing construct, no doc-Read is required.

### Rule 9 — Second-attempt stop: re-read before retrying a failed UI fix

If a UI edit deployed in the current turn did not render as intended — signaled by the user saying things like "hat nicht geklappt", "geht nicht", "sieht nicht so aus", "funktioniert nicht", or equivalent — the **next** UI edit attempt in response MUST be preceded by a fresh `Read` on the relevant HyUI doc file, and the Doc-check block (Rule 7) must quote at least one property or behavior that was NOT consulted in the first attempt.

No guessing two changes in a row. If the docs don't cover the case, say so and escalate to the user (ask for approach) instead of iterating blindly.

## Build

```bash
./gradlew clean build
```

JAR output: `build/libs/ScrubyCompanion-v<version>.jar` (z.B. `ScrubyCompanion-v1.3.8.jar`)

## Testing

No unit test framework — verify via `./gradlew clean build` (compilation) and manual in-game testing.

## Reference Assets

Hytale reference assets (NPC roles, templates, attitudes, etc.) are at:
`vendor/hytale/reference-assets/Server/`

## Key Rules

- Only use verified Hytale API functionality. Do not speculate about APIs that have not been confirmed to exist in the codebase or server JAR.
- **NEVER mention third-party mods in code, comments, or commit messages.** Competitive research (docs/KOKURENZ_MODS/) is for learning only. No names, references, or attributions to other mods in any source file, Javadoc, or git history.
