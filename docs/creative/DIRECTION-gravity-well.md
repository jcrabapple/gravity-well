<!--
  Authority: Creative Direction & Compose Token Spec
  Deliverable: Gravity Well
  Status: Updated (Chat-First Dark Polish)
-->
# Gravity Well - Creative Direction & Compose Design Spec

## 1. Creative Brief & Chat-First Evolution

**Reading the brief:** Pair the full power of a self-hosted Antigravity (`agy`) gateway with a calm, spacious, modern dark chat experience on Android — zero removed functionality, clean 3-tab navigation (`Chats`, `Status`, `Settings`), right-aligned user bubbles, editorial full-width AI markdown, compact inline Thinking & Tool pills, and non-blocking docked approval/question cards.

### Ideation Method
Applied **Lateral Provocations** (Reversal + Distortion) combined with structural theft from physical avionics and audio hardware. Rejected the baseline distribution of AI mobile apps (chat bubbles, floating action buttons, pastel Material You surfaces, circular donut charts) before drafting.

### Directions Explored

1. **Vernier Rig** - Precision monochrome instrument chassis with hairline ledger strata and hardware-grade interlocks
   - **How it works:** Treats every session as an oscilloscope bay. Turns are full-bleed horizontal strata divided by `1dp` rules and `3dp` semantic edge rails instead of chat bubbles. All telemetry (context tokens, cursor seq, quota countdowns, CPU/RAM) renders in tabular monospace (`tnum`) along fixed vertical axes. Dangerous actions (`session/request_permission`, `agy update`) use amber hardware interlocks docked at the bottom edge so the user never loses sight of the diff or command being approved.
   - **Precedent:** Teenage Engineering TP-7 Field System, Tektronix bench oscilloscopes, Linear's monochrome density.
   - **Risk:** Can feel austere or cramped on sub-380dp phones if full-bleed code/diff wells do not keep clean `16dp` prose margins and `48dp` touch targets.
   - **Buildable first step:** Define `GravityWellColors`, `GravityWellTypography`, and `GravityWellShapes` in `theme/` with `dynamicColor = false` and `0.dp` shadow elevation across all surfaces.

2. **Type-Specimen Broadsheet** - Warm archival paper manuscript with marginalia tool footnotes
   - **How it works:** Formats agent prose as a long-form technical broadsheet (`#F4F1EA` bone canvas, `#1A1917` ink) with serifed display headers and collapsed tool calls tucked into margin callouts.
   - **Precedent:** iA Writer, New York Review of Books, Panic Nova light mode.
   - **Risk:** High luminance strains OLED screens during long backgrounded monitoring, and syntax diffs, crash logs, and permission red/amber states lose urgency on warm paper.
   - **Buildable first step:** Build a high-contrast light token sheet with tabular serif/mono pairing.

3. **Switchboard Rack** - Eurorack patch-bay tile matrix
   - **How it works:** Breaks every screen into stackable 4U horizontal hardware modules (Host Bay, Process Slot, Queue Tape, Quota Meter) linked by status LEDs and patch labels.
   - **Precedent:** Mutable Instruments faceplates, Ableton Live Session View, tmux pane multiplexers.
   - **Risk:** Persistent module headers consume ~160dp of vertical height; when the Android IME opens for `@file` autocomplete and slash chips above a docked approval bar, the visible transcript collapses to a narrow slit.
   - **Buildable first step:** Prototype a collapsible module header component with fixed `48dp` slot height.

**The safe one I'm refusing:** The **Material You AI Chat Wrapper** (`dynamicColor = true` pastel purple/teal rounded `24dp` chat bubbles aligned left/right, a floating "+" FAB, a slide-out navigation drawer for chat history, full-screen modal alert dialogs for tool permissions, and oversized circular donut charts for quota). It wastes 30% of horizontal phone width on bubble padding, fragments unified diffs inside narrow cards, masks dangerous permissions in friendly wallpaper colors, and lies about what `agy acp --stdio` is (a supervised OS child process, not a friend texting you).

**Pick:** **Vernier Rig** because it gives code, diffs, streaming tokens, permission gates, and host vitals maximum horizontal real estate on `<600dp` phones while expanding cleanly into a two-pane instrument bench on `>=600dp` tablets, with zero visual clutter competing against live machine state.

---

## 2. Visual & Compose Token Specification (`theme/`)

### Hard Theme Invariants
- **`dynamicColor = false`** strictly enforced in `GravityWellTheme`. System wallpaper colors (`dynamicDarkColorScheme`) are never read.
- **Single Canonical Theme:** Dark instrument chassis (`ChassisVoid` `#0B0D10`). `isSystemInDarkTheme()` does not switch to a light theme.
- **Zero Drop Shadows:** Elevation is communicated solely through tonal surface steps and `1.dp` hairline borders (`0.dp` `shadowElevation` and `tonalElevation` everywhere, including `NavigationBar`, `NavigationRail`, and `ModalBottomSheet` overrides).
- **Zero Literal Hex in UI:** All composables under `ui/` MUST read from `GravityWellTheme.colors` (`LocalGravityWellColors`) or `MaterialTheme.colorScheme`. Literal `Color(0x...)` declarations are permitted ONLY inside `theme/Color.kt`.

### Color Tokens (`GravityWellColors`)

| Token | Hex | Role & Usage |
|---|---|---|
| `chassisVoid` | `#0B0D10` | App canvas, agent prose stratum background (full-bleed) |
| `stratumBase` | `#101318` | Top bars, bottom navigation, session rows, sheet background |
| `stratumRaised` | `#161B22` | Input fields, telemetry strip, chip fill, secondary buttons |
| `stratumUser` | `#171D26` | User prompt stratum background |
| `stratumWell` | `#080A0D` | Recessed code blocks, tool `rawInput`/`rawOutput`, log viewport |
| `hairline` | `#242B36` | Default `1.dp` dividers, card/row strokes, tool gutter border |
| `hairlineStrong` | `#343E4F` | Focused inputs, active segmented borders, sheet handles |
| `inkPrimary` | `#E6EDF3` | Headings, prose, command titles, primary numeric values |
| `inkSecondary` | `#9BA7B8` | Metadata, file paths, subtitles, collapsed summaries |
| `inkMuted` | `#657183` | Timestamps, line numbers, disabled states, placeholders |
| `inkInverse` | `#0B0D10` | Text/icons atop solid signal fills (`signalCyan`, `signalAmber`) |
| `signalCyan` | `#38BDF8` | Primary interactive accent, active stream rail, live tab indicator |
| `signalCyanDim` | `#122634` | Selected chip tint, active workspace filter fill |
| `signalAmber` | `#F59E0B` | User rail, permission gate border/CTA, queue-paused warning, 30% quota threshold |
| `signalAmberDim` | `#2A1F0D` | Docked permission/question bar surface, warning banner fill |
| `signalEmerald` | `#34D399` | Verified 3-leg check, healthy quota (`>=30%`), daemon active, completed tool |
| `signalEmeraldDim` | `#112A22` | Verified status pill background |
| `signalCrimson` | `#F87171` | Destructive deny/revoke/stop, error state, `<10%` critical quota, crash alert |
| `signalCrimsonDim` | `#33171B` | Error banner background, reject action hover/fill |
| `signalViolet` | `#A78BFA` | `/thinking` reasoning block accent, `gateway` custom title tag |
| `signalVioletDim` | `#1F1933` | Collapsed `/thinking` badge tint |
| `diffAddBg` | `#112A22` | Unified diff `+` line background |
| `diffAddWordBg` | `#1B4737` | Word-level diff added token highlight |
| `diffAddInk` | `#34D399` | Unified diff `+` gutter prefix and added text |
| `diffDelBg` | `#33171B` | Unified diff `-` line background |
| `diffDelWordBg` | `#542128` | Word-level diff removed token highlight |
| `diffDelInk` | `#F87171` | Unified diff `-` gutter prefix and deleted text |
| `diffHunkBg` | `#141A24` | Unified diff `@@` hunk header strip |

### Material 3 `darkColorScheme` Mapping
Map `primary = signalCyan`, `onPrimary = inkInverse`, `primaryContainer = signalCyanDim`, `onPrimaryContainer = inkPrimary`, `secondary = signalAmber`, `onSecondary = inkInverse`, `secondaryContainer = signalAmberDim`, `onSecondaryContainer = inkPrimary`, `tertiary = signalViolet`, `onTertiary = inkInverse`, `background = chassisVoid`, `onBackground = inkPrimary`, `surface = stratumBase`, `onSurface = inkPrimary`, `surfaceVariant = stratumRaised`, `onSurfaceVariant = inkSecondary`, `outline = hairlineStrong`, `outlineVariant = hairline`, `error = signalCrimson`, `onError = inkInverse`, `errorContainer = signalCrimsonDim`, `onErrorContainer = signalCrimson`, `scrim = Color(0xCC050709)`.

### Typography (`GravityWellTypography`)
Uses system `FontFamily.SansSerif` for readable prose/UI and `FontFamily.Monospace` for code, paths, diffs, commands, and telemetry. Every numeric readout style sets `fontFeatureSettings = "tnum"`.

| Token | Family | Size / LineHeight | Weight | Tracking | Notes |
|---|---|---|---|---|---|
| `displayMetric` | `Monospace` | `24.sp / 28.sp` | `SemiBold` | `-0.5.sp` | `tnum`; quota %, vitals headline |
| `headlineSection` | `SansSerif` | `18.sp / 24.sp` | `SemiBold` | `-0.2.sp` | Screen & sheet titles |
| `titleBar` | `SansSerif` | `15.sp / 20.sp` | `Medium` | `0.sp` | Top bar session/host title |
| `bodyProse` | `SansSerif` | `15.sp / 22.sp` | `Normal` | `0.sp` | Streaming Markdown & user prompt text |
| `bodyCompact` | `SansSerif` | `13.sp / 18.sp` | `Normal` | `0.1.sp` | Rows, descriptions, settings |
| `labelTelemetry` | `Monospace` | `11.sp / 14.sp` | `Medium` | `0.6.sp` | `tnum`; uppercase section tags, status pills |
| `monoCode` | `Monospace` | `12.5.sp / 18.sp` | `Normal` | `0.sp` | `tnum`; code blocks, diffs, tool I/O, logs |
| `monoMicro` | `Monospace` | `10.5.sp / 14.sp` | `Medium` | `0.4.sp` | `tnum`; cursor `#43`, timestamps, line nums |

### Shapes, Spacing & Touch Floor
- **Grid:** `4.dp` base unit (`4, 8, 12, 16, 20, 24.dp`). Prose horizontal margin: `16.dp`. Full-bleed diff/code wells span edge-to-edge inside `0.dp` horizontal margin with `12.dp` internal padding.
- **Corner Radii (`GravityWellShapes`):**
  - `none = RoundedCornerShape(0.dp)`: Full-bleed ledger turns, docked bars, list rows, hairline gauges.
  - `xs = RoundedCornerShape(2.dp)`: Segmented fuel bar ticks, status rails.
  - `sm = RoundedCornerShape(4.dp)`: Code wells, diff blocks, slash chips, status badges, tool gutters.
  - `md = RoundedCornerShape(8.dp)`: Bottom sheets, modal confirm cards, composer input well, vitals tiles.
  - Forbidden: `>=16.dp` pill/chat bubbles on turn items.
- **Touch Floor:** All interactive triggers (approval buttons, `Send Now`, chip deletes, gutter expanders, nav items) maintain `minWidth = 48.dp, minHeight = 48.dp` via layout size or touch target padding (`defaultMinSize`).

---

## 3. Adaptive Shell (`ui/shell/`)

Breakpoint: **`600.dp` window width** (`WindowWidthSizeClass` / `maxWidth >= 600.dp`).

### Top-Level Destinations (4 Fixed Lanes)
1. **Sessions** (`Icons` / geometric stack): Workspace & Session Browser
2. **Console** (terminal reticle): Live Session Console (if no session attached, shows quick-attach list of live sessions + "New Session" prompt)
3. **Observatory** (gauge / pulse): Quota, Host Vitals, External Radar, Daemon, Update, Logs
4. **Deck** (switchboard / sliders): Control Deck (MCP, Plugins, Agents, Permission Rules, Allowlisted Settings, Hosts)

### Layout Behavior
- **Compact (`<600.dp`):**
  - Bottom `NavigationBar` (`60.dp` height, `stratumBase` `#101318`, `1.dp` top `hairline` `#242B36`, `tonalElevation = 0.dp`).
  - Active item uses a `3.dp` high `signalCyan` top bar inside the slot + `inkPrimary` label (`labelTelemetry`), inactive uses `inkMuted`. No rounded pill indicator background.
  - **Console route rule:** When actively inside a specific `session_id` Console on `<600.dp`, hide the bottom `NavigationBar` so the thumb-zone composer and docked approval bar sit flush at the bottom edge (with a clean back chevron in the top bar returning to `Sessions`).
- **Expanded (`>=600.dp`):**
  - Persistent left `NavigationRail` (`72.dp` width, `stratumBase`, `1.dp` right `hairline`), compact top host badge.
  - **Sessions + Console Two-Pane Split:** `Sessions` occupies a `360.dp` fixed left master column (`1.dp` right `hairline`); `Console` fills the remaining right detail canvas so the user can monitor multiple live sessions while streaming one.
  - **Observatory & Deck Two-Column Grid:** Vitals, quota groups, and ecosystem lists arrange into 2 equal columns (`12.dp` gutter).
- **Persistent Host Pill:** Top app bar on `Sessions`, `Observatory`, and `Deck` displays `[green/amber dot] host.label` (e.g. `aurora · v1.5.15`) as a tappable switcher opening the Host selector sheet.

---

## 4. Host Onboarding & 3-Leg Pre-Flight (`ui/hosts/`)

Adding or verifying a gateway host renders as a **3-Stage Pre-Flight Sequencer**, never a blind URL+token form.

### Form Inputs
- **Host Label:** e.g. `aurora` (defaults to `hostname` returned by Leg 2 if left blank).
- **Base URL:** Monospace field with `https://` prefix hint.
  - In release builds (`!BuildConfig.DEBUG`), `http://` URLs are blocked inline with error `"HTTPS required in release builds (Tailscale Serve / TLS proxy)"`.
  - In debug builds (`BuildConfig.DEBUG`), `http://` displays a `signalAmber` badge: `"DEBUG CLEARTEXT LAN MODE - never use over untrusted networks"`.
- **Bearer Token:** Password-masked monospace input (`KeyboardType.Password`, visibility eye toggle). Stored strictly via `AndroidKeyStore` (`AES/GCM/NoPadding`, random 12B IV) only after Leg 3 succeeds; never written to SQLite.

### 3-Leg Diagnostic Sequencer
Button `"Run Pre-Flight Check"` executes Legs 1 -> 2 -> 3 sequentially (`D13`), updating three stacked `stratumRaised` rows (`1.dp` `hairline` border, `4.dp` radius):
1. **LEG 01 · REACHABILITY & TLS** (`GET /api/health`)
   - Displays `protocol_version == 1`, `agy_version`, and round-trip latency (`e.g. 28 ms`).
2. **LEG 02 · AUTH & CAPABILITIES** (`GET /api/info`)
   - Validates `Authorization: Bearer` against `hmac.compare_digest`; prints `hostname`, `default_cwd`, and capability chips (`resume`, `elicitation`, `gateway_title_override`, and explicit `fork: unsupported` flag).
3. **LEG 03 · LIVE STREAM TICKET** (`POST /api/ws-ticket` -> `WS /api/ws?ticket=...`)
   - Confirms single-use ticket minting and receipt of the server `hello` frame + `latest_cursor`.

- **Failure Anatomy:** Any failed leg halts subsequent legs, switches its row rail to `signalCrimson`, and expands an inline monospace diagnostic box showing `status`, `error.code`, and `error.message` (e.g. `401 unauthorized`).
- **Commit Gate:** `"Save Verified Host"` remains disabled until all 3 legs show `VERIFIED` (`signalEmerald`).

---

## 5. Workspace & Session Browser (`ui/sessions/`)

### Header & Search Rail
- **Scope Tabs:** Compact 3-segment strip (`ALL` | `LIVE` | `ARCHIVED`) styled with `1.dp` `hairline` border and `signalCyanDim` active fill (never oversized rounded pills).
- **FTS5 Search Input:** Single-line `stratumWell` bar querying `GET /api/sessions?q=...` (live substring + archived `conversations_fts`).
- **Workspace Chips:** Horizontally scrollable `cwd` basename filter chips (`All Workspaces`, `gravity-well`, ...).

### External Session Radar Strip
- Driven by `GET /api/observatory/status` and `presence_update` WS frames.
- **Liveness rule (`D10`):** Displays ONLY `FLOCK`-verified active external `agy` processes on the host (never raw `.lock` file counts).
- Compact collapsible amber-or-cyan strip below the filter rail: `"EXTERNAL RADAR · 1 ACTIVE HOST SESSION"` -> expands to show `title`, `cwd`, and `HOST EXTERNAL` vs `SUPERVISED` badge. External unsupervised sessions are clearly labeled read-only unless resumed via gateway.

### Telemetry Session Row (Zero Drop-Shadow Cards)
Full-width `stratumBase` row (`1.dp` bottom `hairline`) with a `3.dp` left status rail:
- `running` / `starting`: pulsing `signalCyan` rail
- `awaiting_approval` / `awaiting_question`: solid `signalAmber` rail + `"GATE"` micro-tag
- `idle`: steady `signalEmerald` rail
- `error`: solid `signalCrimson` rail
- `archived`: `inkMuted` rail
- **Row Layout:**
  1. Line 1: `title` (`titleBar`), optional `GATEWAY` violet micro-pill if `title_source == "gateway"`, right-aligned `step_count` + relative time (`monoMicro`).
  2. Line 2: `cwd` basename + truncated path (`monoMicro`, `inkSecondary`).
  3. Line 3 (live only): `model_id · thought_level · mode_id` (`labelTelemetry`) + compact preview snippet (`bodyCompact`).
- **Row Actions:** Tap opens Live Console (or auto-prompts `Resume` / `Resume + Load History` for archived sessions). Overflow menu: `Rename` (`PATCH /api/sessions/{id}`), `Close` (`DELETE /api/sessions/{id}`), and `Fork` (when `capabilities.fork == false`, shows disabled state with `"-32601 unsupported on agy 1.5.15"` footnote per `D4`).

### New Session Sheet
- `ModalBottomSheet` (`stratumBase`, `0.dp` tonalElevation, `8.dp` top corners, `1.dp` top `hairlineStrong`):
  - `cwd` text field (pre-filled with `host.default_cwd`) + recent workspace quick-chips
  - `--add-dir` multi-path chips (`spawn_add_dir`)
  - Initial `Model`, `Thought Level`, `Mode`, optional `Agent`, and `Sandbox` toggle (`sandbox = true/false`).

---

## 6. Live Session Console (`ui/console/`)

### Top Bar & 28dp Telemetry Sub-Header
- **Top Bar (`52.dp`):** Back chevron (compact only), editable session title (tap to rename via `PATCH`), status pill (`idle`, `running`, `awaiting_approval`, etc.), `Cancel` hardware stop button (`signalCrimson` outline, visible while `running` -> `POST /api/sessions/{id}/cancel`), and overflow (`brain/ Artifacts`, `Workspace Rules`, `Close Session`).
- **Telemetry Sub-Header (`28.dp`, `stratumRaised`, `1.dp` bottom `hairline`):**
  - Left: tappable config chip `[model · effort · mode]` (`monoMicro`, opens Model/Effort/Mode/Sandbox sheet).
  - Center: `1.dp`-framed context fill bar + `8.7k / 1.0M` `tnum` readout (`usage_update`).
  - Right: stream state indicator (`LIVE #43` `signalEmerald`, `REPLAYING` `signalCyan`, `RECONNECTING` `signalAmber`, `RESYNCED` `signalViolet`).

### Turn Stream Ledger (Zero Material 3 Chat Bubbles)
All stream entries render inside a `LazyColumn` with `0.dp` horizontal card margins (full-bleed strata separated by `1.dp` `hairline` rules):

1. **User Prompt Stratum (`user_message_chunk`):**
   - `stratumUser` (`#171D26`) background, `3.dp` solid `signalAmber` left rail, `USER` `labelTelemetry` header + timestamp, optional attachment badge strip, `bodyProse` selectable text.
2. **Streaming Agent Markdown (`agent_message_chunk`):**
   - `chassisVoid` (`#0B0D10`) background (zero bubble box), `3.dp` `signalCyan` left rail while streaming (fades to `hairline` once turn settles).
   - Incremental Markdown renderer: headings, bold/italic, ordered/unordered lists, tables (horizontally scrollable inside `1.dp` hairline frame), inline code (`stratumRaised` `#161B22` `4.dp` span), and fenced code blocks (`stratumWell` `#080A0D` well with top language bar + one-tap `Copy` button).
3. **Collapsible `/thinking` (`agent_thought_chunk`):**
   - Rendered as a recessed `stratumWell` strip with `1.dp` `hairline` border and `2.dp` `signalViolet` left rail.
   - **While streaming:** shows live 2-line clipped monospace preview (`"THOUGHT · streaming..."`) with expand toggle.
   - **Once settled:** collapses automatically to a single `36.dp` header row (`"THOUGHT · 142 lines"` or char count + expand chevron). Expanding reveals full `monoCode` reasoning in `inkSecondary`.
4. **Collapsible Tool Gutters (`tool_call` / `tool_call_update`):**
   - Single-line collapsed header (`40.dp` min height): `[KIND]` mono badge (`EXECUTE`, `EDIT`, `READ`, `SEARCH`), concise `title`, live status dot (`pending`/`in_progress` cyan pulse, `completed` emerald, `error` crimson).
   - Expanded body: `stratumWell` (`#080A0D`) showing structured `rawInput` / `rawOutput` monospace blocks.
   - **Unified & Word Diff Renderer:** When `diff` or edit hunks exist, renders a dedicated diff panel inside the gutter with a **`UNIFIED | WORD`** segmented toggle in its header:
     - `UNIFIED`: line numbers (`monoMicro`, `tnum`), `+` (`diffAddBg`/`diffAddInk`), `-` (`diffDelBg`/`diffDelInk`), `@@` hunk headers (`diffHunkBg`).
     - `WORD`: inline token-level span highlights (`diffAddWordBg` / `diffDelWordBg`) inside changed lines for fast scanning on narrow `<600dp` screens.
5. **Subagent / Swarm Strip:**
   - Pinned `36.dp` horizontal strip directly beneath the 28dp Telemetry Sub-Header whenever active child/subagent/swarm tool calls are in flight (`SWARM · 3 WORKERS` + compact status pills `[researcher ●] [coder ●]`). Tapping scrolls to or opens the selected worker's tool gutter output.

### `brain/` Artifact Viewer
- Accessed from Console overflow menu (`GET /api/sessions/{id}/artifacts`).
- Displays clean directory-grouped rows (`logs/transcript.jsonl`, `steps/*/output.txt`, `tasks/*.log`, `scratch/`) with MIME tag and byte size.
- Text/JSONL artifacts open in a `stratumWell` monospace reader displaying a `"SECRET-REDACTED"` shield badge; PNG/JPEG/WebP artifacts render via authenticated Coil/OkHttp preview with zoom/pan.

### Docked Approval & Question Bar (Never a Blocking Center Popup)
Docked directly above the composer so the user can still scroll and inspect the diff/tool gutter above it:
- **Permission Gate (`permission_requested`):**
  - `stratumBase` tinted with `signalAmberDim` (`#2A1F0D`), `2.dp` top & left `signalAmber` border.
  - Header: `PERMISSION GATE` + `tool_call.kind` + exact `CommandLine` / `rawInput` monospace preview (up to 4 lines, expandable).
  - Action row dynamically bound to `options` (`R6`/`D7`):
    - `allow_once` -> solid `signalAmber` primary button (`Allow Once`)
    - `allow_always` -> outlined `signalCyan` button (`Allow Workspace Rule`, with sub-caption `"Scoped to cwd · revocable in Deck"`)
    - `reject_once` / `deny` -> outlined `signalCrimson` button (`Deny`)
- **Question Broker (`question_requested` / `elicitation/create`):**
  - `signalCyanDim` header (`AGENT QUESTION`), renders `requestedSchema.properties` dynamically preserving exact keys (`q_0`, `q_1`):
    - `enum` / `enumNames`: single-select `48.dp` tactile option rows with `1.dp` active `signalCyan` border
    - `string` / `integer`: inline `stratumWell` field
    - `boolean`: switch row
  - Footer: `Submit` (`action="accept"`, disabled until `required` keys satisfied), `Decline`, `Cancel`.

### Queued Follow-Up Tray
Docked between the stream and composer whenever `queued_followups` is non-empty or `queue_paused == true`:
- **Paused Banner (`queue_paused == true`):** `signalAmberDim` strip: `"QUEUE PAUSED AFTER CANCEL/ERROR"` + `Resume` action.
- **Queue Rows:** Compact `44.dp`+ rows showing `#pos`, prompt snippet, attachment count, `Edit`, `Delete`, and **`Send Now`** (`signalCyan` text button).
- **Draft Preservation Invariant (`D6`):** Tapping `Send Now` invokes `POST /api/sessions/{id}/queue/{item_id}/send-now` (`interrupt=true`) and **MUST NOT mutate or clear** the user's active text in `composer_drafts`.

### Thumb-Zone Composer Dock
- **IME-Bounded `@file` Autocomplete:** Appears directly above the composer when typing `@<token>`, constrained to `maxHeight = 180.dp` inside `imePadding()` so it never obscures the top telemetry bar or gets clipped behind the keyboard. Rows show relative path + workspace root tag (`GET /api/sessions/{id}/files`).
- **Slash Command Chips (`/`):** Single-row horizontal `LazyRow` of `4.dp`-radius `stratumRaised` mono chips (`/plan`, `/compact`, etc. from `available_commands_update`), revealed by typing `/` or tapping the `/` button.
- **Magic-Byte Attachment Preview Strip:** Horizontal thumbnail/file strip above the input field. Client enforces `<= 10 MiB` pre-check; shows thumbnail (images) or MIME badge (PDF / UTF-8 text), verified byte size, upload spinner, or inline `signalCrimson` `413`/`415` error badge, plus `48.dp` remove target.
- **Composer Row:** `[Attach]` `[ / ]` `[Multi-line stratumWell field]` `[SEND / QUEUE button]`. When session is `running`, primary button reads `QUEUE` (`signalAmber` tint) to signal FIFO enqueue rather than immediate dispatch.

### Model / Effort / Mode / Sandbox Sheet
- Triggered from the 28dp Telemetry Sub-Header chip.
- Reads live `configOptions` (`D5`):
  - **Model:** grouped list of base family IDs (`gemini-4-neon`, `gemini-3.8-flash`, `claude-sonnet-5-5`, etc.)
  - **Thought Level:** 4-segment hardware selector (`LOW | MEDIUM | HIGH | XHIGH`)
  - **Mode:** 4-row radio selector (`default`, `accept-edits`, `auto`, `plan`) with explicit risk indicators (`auto` marked `signalAmber` `"Auto-executes tools"`)
  - **Sandbox:** read-only status badge on active sessions (set at creation time).

---

## 7. Observatory (`ui/observatory/`)

1. **Quota Gauges & Reset Countdowns (`GET /api/observatory/usage`):**
   - Grouped by `groups[]` (`Gemini Models`, `Claude and GPT models`, `Gemini 4 Argon`).
   - Each bucket (`5h`, `weekly`) uses a **10-segment linear fuel bar** (`8.dp` height, `2.dp` segment corner, zero bloated donuts) paired with `displayMetric` `tnum` percentage (`89.1%`) and a live ticking `T-04h 12m 08s` reset countdown (`signalEmerald` `>=30%`, `signalAmber` `10%..29.9%`, `signalCrimson` `<10%`).
   - Header shows `cached_at` timestamp, `STALE` amber pill if stale, and manual `Refresh` (`refresh=true`).
2. **Host Vitals (`GET /api/observatory/status`):**
   - 4-up compact telemetry grid (2x2 on `<600.dp`, 4x1 on `>=600.dp`): `CPU % / Load 1m·5m`, `RAM Used/Total`, `NVMe Used/Total`, `External Radar Active`.
3. **External Session Radar:**
   - Lists `FLOCK`-verified active `agy` sessions (`cwd`, `title`, `SUPERVISED` vs `HOST EXTERNAL`).
4. **Remote-Control Daemon Toggle:**
   - Displays `agy remote-control status` (`ACTIVE` emerald / `INACTIVE` muted), `instance_name`, `Start` (optional name + `session_scoped` checkbox) and `Stop` button.
5. **Confirm-Gated `agy update`:**
   - Shows current `agy_version` + `update_status.json` message.
   - Tapping `"Update Antigravity CLI"` opens a mandatory **2-Step Confirmation Sheet** showing exact command (`agy update`) and requiring explicit `"Confirm & Run Update"` tap (`{"confirm": true}`) before `POST /api/observatory/update`, then prints the redacted stdout result.
6. **Crash & Log Viewer (`GET /api/observatory/logs`):**
   - Source toggle (`cli.log` | `crashes/`), crash file selector when `crashes/` is chosen, search query `q`, tail selector (`200 | 500 | 2000`), and monospace `stratumWell` log viewport highlighting `ERROR` (`signalCrimson`), `WARN` (`signalAmber`), and `[REDACTED]` token pills (`signalVioletDim`).

---

## 8. Control Deck (`ui/deck/`)

Structured as 4 clean sub-tabs (`MCP` | `PLUGINS` | `AGENTS & RULES` | `SYSTEM`):

1. **MCP Servers:** Lists `stdio`/`http` servers with `disabled` toggle (`enable`/`disable`), command/URL (`monoMicro`), and `env`/`headers` key names paired with `[REDACTED]` badges (never revealing secret values). `+ Add MCP` sheet collects `name`, `type`, `target`, `args`, `env`, `headers`; `Remove` requires confirm.
2. **Plugins:** Lists user + builtin (`swarm`) plugins with enable/disable/uninstall actions, `Validate` (`--strict` toggle), and `Install` target input.
3. **Agents & Permission Rules:**
   - Custom Agents list (`~/.gemini/agents/*.md` read-only + `~/.gemini/antigravity-cli/agents/*.md` editable/deletable with slug validation `[a-z0-9][a-z0-9_-]{0,63}`).
   - **Workspace Allow-Rules (`GET/DELETE /api/permissions/rules`):** Dedicated safety table listing every granted `allow_rule` (`workspace_root`, `tool_kind`, `command_prefix`) with an immediate `signalCrimson` **`Revoke`** button (`D7`).
4. **Allowlisted Settings, Keepalive & Hosts:**
   - **Allowlisted `settings.json` (`D12`):** Strictly TWO switches: `general.previewFeatures` and `general.enablePromptCompletion`, with a muted caption: `"security.* and auth keys are host-locked and never exposed."`
   - **Background Keepalive & Notifications (`D16`):** Explicit toggle to enable/disable `GravityWellKeepaliveService` + notifications (stopping the service and cancelling active notifications immediately when toggled off) + `"Open System Notification Settings"` action.
   - **Host Manager:** Switch active host, trigger 3-leg re-verify, or remove host.

---

## 9. Notification & Keepalive Surface (`service/`, `res/drawable/ic_notification.xml`)

- **`ic_notification.xml` (`24dp x 24dp` monochrome vector):**
  - Strictly `#FFFFFF` vector paths on transparent background (`viewportWidth="24"`, `viewportHeight="24"`), zero colors, zero gradients, zero drop shadows: a stepped orbital gravity-well (outer broken ring + inner eccentric ring + center 2dp crosshair dot) readable down to `16dp` status-bar scale.
- **Ongoing `GravityWellKeepaliveService` (`dataSync`):**
  - Compact status notification: title `"Gravity Well · <host.label>"`, body `"<N> live sessions · <M> awaiting gate"` (zero tokens or prompt secrets in notification text or `Intent` extras).
  - Single action: **`Stop`** (`KeepaliveActionReceiver`) to tear down keepalive cleanly.
- **Priority Approval Alert:**
  - Issued when `permission_requested` or `question_requested` arrives while app is backgrounded (guarded inline by `SDK_INT >= 33` + `POST_NOTIFICATIONS` permission check + `try/catch SecurityException` per `D16`).
  - Content: session title + concise tool title / command summary.
  - Quick actions (`permission_requested` only): **`Approve Once`** and **`Deny`** (`PendingIntent.FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`). **`allow_rule` is NEVER offered from a notification shade action.**
  - Tap body: launches `MainActivity` (`launchMode="singleTask"`) with `(hostId, sessionId)` queued cleanly across cold start and warm resume.

---

## 10. Critique Audit Checklist (for `w3-creative-critique`)

The Wave 3 creative audit scores the built Android code as **`HOLDS`** only if all checks pass:
1. `dynamicColor = false` hardcoded in `Theme.kt`; zero `dynamicDarkColorScheme` / `dynamicLightColorScheme` calls.
2. Zero literal `Color(0x...)` outside `theme/Color.kt`; `0.dp` shadow/tonal elevation on bars, rows, and sheets.
3. Zero Material 3 rounded chat bubbles (`>=16.dp` corner pill containers) in `ui/console/`.
4. Tabular monospace (`fontFeatureSettings = "tnum"`) applied to token counters, quota percentages, countdowns, and diff line numbers.
5. Permission/Question gates are docked in-console (not center-blocking modals) and dynamically driven by `options` / `requestedSchema`.
6. Queued `Send Now` preserves `composer_drafts`; `@file` autocomplete is IME-bounded above composer.
7. Observatory uses linear segmented fuel bars + countdowns (no donut charts) and two-step confirm for `agy update`.
8. `ic_notification.xml` is strict 24dp `#FFFFFF` monochrome vector; notification actions never expose `allow_rule` or secrets.

