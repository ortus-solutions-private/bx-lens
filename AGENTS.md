# Project Guidelines

## Purpose

BX Lens is a BoxLang module with two surfaces: a request level debug bar (a small Alpine.js UI injected before `</body>` on HTML responses) and a standalone console for one server at `/~bxlens/index.bxm`. Java collectors and services gather data in memory. BX Lens is a product of Ortus Solutions and is not open source: do not write open source or Apache claims in code, docs or `box.json`. BX Insights is the separate observability product for clusters, history and alerting, so do not add cross node analytics or a database here. The only persistence is the Plus disk store (`errors.json`, `reports.json`), the saved settings overrides, the bar layout and the audit log.

## Architecture

- `src/main/bx/ModuleConfig.bx` declares every setting with its default, the public mapping `~bxlens` (`usePrefix: false`) and wires the lifecycle. Keep it in sync with `docs/configuration.md`. The schema is `bar`, `console`, `collect`, `tabs`, `store`, `ai`, `checks`, `dev`, `access`, plus the older blocks. `checks.*` and `dev.*` are declared now. `console.allowHeapDump` is read with a default of `false` and is not declared yet: declare it when you next touch the schema.
- `src/main/java/ortus/boxlang/modules/bxlens/`
  - `LensService`: singleton. Settings, collectors, history, injector. Collectors register as interceptors.
  - `LensConfig`: parsed, immutable settings, including the `bxsecret:` console password. `AccessGuard`: one guard per surface (`bar.access`, `console.access`) plus `access.allowedHosts` and `access.requireHeader`; `all` is downgraded to loopback for the bar unless `bar.allowAllIPs`.
  - `ConsoleRouter`: all console routes, security headers and the SSE stream. `ConsoleAuth`: password check for two roles (admin, viewer), in-memory sessions, CSRF, per-IP lockout. `ConsoleData`: executors, tasks, system and threads from core services and JDK beans. `LayoutStore`: the saved bar layout (`config/bxlens-layout.json`). `Licensing`: BoxLang+ detection through `bx-plus`.
  - `ClientIp`: the client address behind a proxy. The header is believed only from `access.proxyPeers` and never turns a remote peer into loopback. `WebExchange.remoteAddr()` uses it, `peerAddr()` is the raw connection.
  - `SettingsRegistry`: the one list of settings an admin may change live (`live` true) and the ones shown as `boxlang.json only`. `SettingsStore`: the saved overrides (`config/bxlens-settings.json` or `console.overridesFile`), validated against the registry on write and on load. `LensService.changeSettings/resetSettings/applySettings` rebuild the config and reconcile collectors.
  - `Audit`: writes `bxlens-audit.log` through the logging service. Never log secrets.
  - Console page data: `DatasourceData` (Hikari numbers, a metrics tracker attached to pools that have none, connection test), `CacheData` (stats, keys capped at 100, values cut at 2 KB), `LogData` (logs directory only, symlinks resolved, tail, search, level filter, offset for the live tail), `EnvironmentData` (config, modules, JVM arguments, variables, properties, and the diagnostic bundle), `QueryStats` (per statement, 500 statements), `ErrorStore` (fingerprint groups, 200 groups, 5 samples each), `Reports` (atomic counters, latency buckets, minute series, 300 URLs), `HeapDumper` (one dump at a time, private temp folder, removed after download or 10 minutes).
  - `AiService`: calls `aiChat` from `bx-ai` when `ai.enabled` and the module exists (rate limited, one at a time). `AiPrompts`: builds the prompts from redacted data only. `util/Secrets`: hides secret-looking names and URL credentials in diagnostic text. `util/Plain`: small helpers and atomic file writes for the disk store.
  - `interceptors/TaskOutcomes`: remembers how each task's last run ended (core does not).
  - `util/Cost`: CPU time and allocation of the request thread. `model/SecurityChecks`: header and cookie Notes (severity `info`).
  - `model/`: `LensRequest` (per request, attached to the request context), `Span`, `IssueEngine`, `Snapshot` (the JSON contract with the UI).
  - `interceptors/collectors/`: one class per panel. They never throw into a request. `Integrations`: the registry of module integrations. `OrmTotals`: bounded totals of the ORM events. `OrmData`: the two bx-orm service calls for statistics, by reflection.
  - `bifs/`: the `lens*` BIFs. The tracking BIFs do nothing when the request is not tracked. `lensReport`, `lensErrors`, `lensQueries`, `lensInflight` and `lensLicense` return plain structs and arrays in any request (`BoxData` converts the maps). `lensConsole()` runs the console and is called only from `src/main/bx/public/index.bxm`.
  - `ext/`: the data only extension API (`LensPanelBuilder`, `LensRegistry`, `CollectHandle`).
  - `store/RequestStore`: bounded in-memory ring buffer.
  - `web/WebExchange`: the only class that touches web-support types (compile only dependency).
- `src/main/bx/assets/`: the bar (`lens.html`, `lens.js`, `lens.css`, scoped under `#bxlens`) and the console (`console.html`, `login.html`, `setup.html`, `console.js`, `console.css`), plus `alpine.min.js` and the icon sprites.
- `harness/`: demo app that produces every kind of data (console password `lens-demo`, `LENS_LICENSE` env, `load.bxm`, `stall.bxm`, `home/config/tasks.json`). `e2e/`: Playwright tests against it.

## Rules that matter

- The console is Java behind the `lensConsole()` BIF. `index.bxm` contains only that call. Add routes in `ConsoleRouter`, data in `ConsoleData`. Every API route needs a session, and every non-GET needs the `X-Lens-CSRF` token. Login needs `X-Lens-Login`. A caller who fails `console.access` gets a plain 404.
- No external requests, ever. The console and the bar must not load scripts, styles, fonts, images or data from another origin, and the Content-Security-Policy stays strict. Alpine.js is vendored, fonts are system fonts, and an e2e test checks this.
- Icons are Phosphor (MIT). Do not link them from a CDN. `tools/build-icons.py` builds `icons.svg` (ids `ph-*`) and `bar-icons.svg` (ids `bxlens-ph-*`, the bar's prefixed subset). Keep `ICONS-LICENSE.txt`.
- Settings: every new setting goes in `ModuleConfig.bx` with a default, is read through `LensConfig` with a default, and is added to `docs/configuration.md`. A change to a name needs a line in the migration table there.
- Licensing: detection must never break Lens. Gate a feature only through `Licensing.has(feature)`. The gated features are listed below and in `Licensing.PLUS_FEATURES`. Everything else is free. Do not gate anything else or invent a split without a decision. Docs must say the split is the current state and may change. To gate a new feature: add it to `Licensing.PLUS_FEATURES`, refuse it on the server with `plusOnly(...)` in `ConsoleRouter` (403 with a message that names BoxLang+; AI answers 409), lock it in the UI with `state.plus` (console) or `ui.plus` (bar) and a "BoxLang+" note or chip, add it to the table in `docs/licensing.md`, and add it to `e2e/tests/free.spec.ts`.
- Roles: `ConsoleAuth` gives `admin` or `viewer`. A viewer may only use GET routes. Routes that return files or secrets are admin only even for GET: list them in `ConsoleRouter.ADMIN_ONLY` (`threads`, `heapdump`, `logfiles`, `bundle`, `cachevalue`, `environment`, `system`); a viewer also loses those pages (`ADMIN_PAGES`) and the values of `console.access`, `access.proxyPeers` and `console.overridesFile`. Every new route that changes state or hands out a file needs a decision on that list, and on whether it is a Plus feature. `console.readOnly` refuses every non-GET except heap dump, a route ending in `/test` and `ai/`. Write an audit line for every change, download and denied attempt.
- Stores are bounded and redacted: every in-memory store has a hard cap and drops the least recently seen entry (`QueryStats` 500, `ErrorStore` 200 groups and 5 samples, `Reports` 300 URLs, caches 100 keys, values 2 KB, logs 2000 lines). Store statement text and never parameter values. Redact before storing, not when showing. Anything a user can paste into a chat goes through `AiPrompts`.
- No persistence beyond the Plus disk store: only `ErrorStore` and `Reports` write to `store.dir`, atomically (`Plain.writeAtomic`), and only when `LensService.diskStoreOn()` is true. Do not add other files or a database. Heap dump files are temporary and must be deleted.
- A new console page needs: a collector id in the `LensService.activate` list (so `SettingsRegistry` gets its switch), an entry in `ConsoleRouter.tabs()`, a `panelOn()` check on its routes, a section in `console.html` and `console.js`, an entry in `docs/configuration.md` and `docs/console/`, and a Playwright test.
- Settings that can be changed live go in `SettingsRegistry` with `live` true and a range. Passwords, access rules, `console.*`, `store.*`, `ai.apiKey` and `ai.links` stay `boxlang.json` only. The rest of `ai.*` is live and admin only (`ai/config`), and `ai.apiKeyEnv` takes the NAME of an environment variable, never a key.
- Collectors never block or throw into the request, and heavy aggregation is async: `LensService.async(...)` queues work for one bounded daemon worker (`util/AsyncWorker`, `async.enabled`, `async.queueSize`; it drops new work when full and counts it). Statistics (`QueryStats`, `ErrorStore`, `Reports`), the history entry and the audit log run there. A reader of those stores calls `LensService.sync()` first. Issue analysis is lazy and belongs to the console. On the request thread only: close spans, take the status, copy headers, and build the bar payload when the bar shows.
- The bar is a snapshot of one request: no issues, no flags, no amber. The page gets a block of about 1 KB. The styles, script, markup, icons and Alpine are files served by `ConsoleRouter` under `assets/` with a content hash in the URL (`BarRenderer`); they hold no data, so they do not need `console.access`.
- The console collects every request when enabled, the bar only for allowed callers. Keep production safe: `collect.level` defaults to `light`, which skips heavy collectors via `ILensCollector.heavy()`, caller lookups and Java stacks. `queries.includeParams` and `orm.enabled` default to false (`orm.enabled` also needs bx-orm installed). Do not use regular expressions on request paths: use the scanners in `util/Text` and `util/Secrets`. One matcher decides what a secret name is: `Secrets.isSecretName`. The snapshot JSON is built lazily (`RequestStore.Entry.json()`); never build it per request.

- Each BoxLang module has its own class loader. Tests on the test classpath see a different `LensService` than the module does, so integration tests reach the module's instance reflectively (see `IntegrationTest`).
- Integrations (`Integrations.java`, `docs/reference/integrations.md`): a module integration is opt in (`collectors.<id>.enabled`, default false), needs its module installed (status `notInstalled`, `available` or `on`; register nothing and offer no switch otherwise), and listens to the events that module announces. Never reflect into another module's classes when events exist; a documented service call meant for tools may be called reflectively, with no dependency on the module. A collector of an integration returns its id from `ILensCollector.integration()`, and `LensService.reconcileCollectors` registers it only while the integration is `on`; module load and unload events reconcile it without a restart. Totals across requests go in a bounded store (`OrmTotals`), never parameter values. Lens cannot force settings of another module (`announceQueryParams`, `generateStatistics` are the app's).
- Every stored record carries the server identity: a new request, error, statistic, report, in flight entry, export, audit line or disk store file takes `serverHost`, `serverIp` and `serverId` from `LensService.getIdentity()` (`util/ServerIdentity`, detected once, never a lookup on a request thread) and a store snapshot has a top level `server`. `X-BxLens-Server` is sent only with the id header and only when `history.serverHeader` is true.
- Lensy (`ops/`, `models/ops/*.bx`): everything it can do goes through the Toolbox facade (`ops/Toolbox`). A new tool is a method with `@AITool` in `LensyTools.bx` (its comment is what the model reads), an entry in `ops/Tools.java` (role, kind, arguments) and a row in `docs/reference/ai-tools.md`; a test keeps the three equal. The Toolbox checks the tool, BoxLang+, the role, `console.readOnly`/`console.actions`/`ai.actions`, the arguments and the limits, redacts and caps the result (`Secrets.loose`, `Sanitizer`, 12000 characters) and writes an `ai.tool` audit line. Every tool needs a role decision (admin only unless the console shows the same data to a viewer). An ACT tool is admin only, needs the approval card (`Approvals`, five minutes, one decision, same session and CSRF) and may never change `ai.*`, `console.*`, `access.*` or `store.*`. No tool takes SQL, restarts, dumps or reads files. Model output is untrusted data: the page renders it with `x-text` and DOM nodes only. bx-ai calls from Java go through `AgentService`, never from a request thread.
- MCP servers of Lensy (`ops/Mcp*.java`): the list is managed by an admin in the console (routes `ai/mcp`, admin only, BoxLang+, CSRF, refused when `console.readOnly`, audit `ai.mcp.change`) and kept under `mcp.servers` in the settings overrides file; an invalid entry is skipped and reported, never blocking startup. The builtin Ortus documentation servers ship disabled, cannot be removed and their address is not editable. Addresses are https only (http for loopback), without credentials, public addresses only, checked on the server when saved and again before every connection (`McpUrls`), no redirects, no headers or credentials are ever sent. Every MCP call goes through `Toolbox.callMcp` (role, enabled and ticked, arguments, limits, approval for a custom server unless trusted, redaction and cap, `ai.mcp` audit line); the call itself is made by the MCP client of bx-ai, through `BxAiMcpClient` and the BoxLang bridge `models/ops/McpBridge.bx`, which sets the guard, no redirects, the size limit and the handshake on it. `aiAgent(mcpServers=...)` and `withMCPServer` are not used for Lensy, because they would put the tools in the agent without the Toolbox gate. A result from a server is untrusted data. Tests for a feature that calls a server use the BoxLang mock in `harness/mock-mcp` or the `FakeMcpServer` of the unit tests (the unit tests of the service use the test class `HttpMcpClient`, the bridge has `BxAiMcpClientIntegrationTest`), never the internet.
- Collectors are shared across threads. Keep per request state in `LensRequest`, never in collector fields.
- Anything sent to the page goes through `Sanitizer` (redaction, depth and size caps) and `Json` (script safe). The UI renders only with `x-text`, never `x-html`.
- Core skips `onRequestEnd` for uncaught errors and aborts, so those requests are finished from `onError` and `onAbort` and get no bar. Do not fight this; document it.
- Core gaps (no `onComponentInvocation`, no cache read events, no SOAP events) are listed in `docs/reference/events.md`. Do not build collectors on events that never fire.
- The first run of a template includes compilation, so slow template warnings on first hit are expected. Tests warm pages up in `e2e/global-setup.ts`.

## Build and test

```bash
./gradlew downloadBoxLang      # once, downloads BoxLang and web support snapshots
./gradlew shadowJar test       # package the module, run unit and integration tests
./gradlew spotlessApply        # REQUIRED before every commit when Java changed
harness/start.sh               # run the demo (DEV=1 SKIP_BUILD=1 to edit the UI live)
cd e2e && npm run e2e          # Playwright. LENS_CHROMIUM=/path/to/chrome if needed
```

Gradle plugin and Maven Central rate limits in sandboxes: point Gradle at a mirror with an init script instead of changing `build.gradle`.

## Conventions

- Follow `.editorconfig` (tabs) and the Ortus formatter in `.ortus-java-style.xml`. Use the `this.` prefix for instance fields. New Java files carry the BoxLang+ header (the same four line header as bx-redis: `[BoxLang]` and `Copyright [2026] [Ortus Solutions, Corp]`), never an Apache header. The `LICENSE` file is the BoxLang+ proprietary license (freeware with limits); do not change its text without the owners.
- No em dashes in docs, comments or UI copy.
- New features need tests: JUnit for Java, Playwright for anything a user can see. Add a harness page when a feature needs a scenario.
- Keep `box.json`, `settings.gradle` and `gradle.properties` aligned when names or versions change. `boxlangVersion` in `gradle.properties` is the BoxLang version the module compiles against.

## Skills

Relevant BoxLang development skills live under `.agents/skills` (restore with `npx skills experimental_install`). Use them for module development, BIFs, interceptors, logging and runtime architecture.

## Bundled modules and gating

- BoxLang AI (bx-ai, the 3.6.0 snapshot build of its development branch) is nested in `modules/bxai` inside the built module. `build.gradle` downloads it (`downloadBxAi`) into `build/cache`, asking the server for a newer one each time. A snapshot has no stable checksum, so `bxAiSha256` is empty and `verifyBxAi` is skipped; when a bx-ai release has the MCP client controls Lens needs, set `bxAiVersion` to it and `bxAiSha256` to its SHA-256. Do not add it to `box.json` dependencies.
- Features gated by `Licensing.has()` (`Licensing.PLUS_FEATURES`). Everything else is open.
  - `cost`: request cost (CPU, allocation) and the slow request sample.
  - `taskActions`: Run now, pause, resume, reload.
  - `cacheActions`: read a cache value, evict, reap, clear.
  - `logDownload`: download a log file.
  - `bundle`: the diagnostic bundle.
  - `heapDump`: heap dumps (still off until `console.allowHeapDump`).
  - `barDesigner`: save or reset a bar layout.
  - `ai`: Explain with AI, Ask Lens and Lensy (chat, tools, approved actions, the AI settings and the connection test). Copy prompt and the ChatGPT and Claude buttons stay free.
  - `diskStore`: errors and reports saved, totals since first install, a longer minute series.
  - `fullHistory`: more than `LensService.FREE_HISTORY` (25) requests in memory.
  - `ormStats`: the console ORM page (event totals, failures, Hibernate statistics and the switch for them). The ORM SQL in the query list stays free, and so does the integration list and its switch.
- The rule: add a feature to `Licensing.PLUS_FEATURES`, gate it on the server with `plusOnly`, gate it in the UI with `state.plus` or `ui.plus`, add it to `docs/licensing.md` and to `e2e/tests/free.spec.ts`.
