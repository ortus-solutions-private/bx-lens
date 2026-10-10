# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

----

## [Unreleased]

### Changed

* Lensy's MCP calls now go through the MCP client of bx-ai instead of a client written in Lens (about 350 lines of Java removed from the module). Lens sets the controls bx-ai gained for this (an address guard that runs before every request, no redirects, an answer size limit, the MCP handshake, the timeout in milliseconds), so the rules stay the same: public addresses only, checked again before every connection, no redirects, no credentials, the Toolbox gate and the 12,000 character cap. The bundled bx-ai is now the 3.6.0 snapshot build of bx-ai, downloaded by the build (it cannot be pinned by a checksum) until a bx-ai release has these controls. A server that sends its tools in pages is read page by page (up to five pages, 100 tools). What changes for people: a connection test or a chat that meets a server that does not answer can take up to twice the time limit, because the handshake and the call each wait up to the limit. Calls to a server are measured in whole seconds
* The assistant is now called Lensy, the Box Agent for your BoxLang server, everywhere you can see it: the drawer, the button, the AI and Ask Lens pages, the system prompt and the docs. Only text changed: the routes (`/api/agent/*`), the settings (`ai.*`) and the audit event names (`ai.tool`, `ai.act`, `ai.chat`...) stay as they were. The audit line of a denied call now reads "Lensy is a BoxLang+ feature" where it said "The assistant". The BoxLang classes are `models/ops/Lensy.bx` and `LensyTools.bx` (were `OpsAgent.bx`, `LensTools.bx`)
* The ORM integration replaced the first ORM spike: the JDK proxy around the Hibernate connection provider, the swap of a private Hibernate field, the Logback fallback on `org.hibernate.SQL` and the reading of Hibernate statistics by reflection are gone. `collectors.orm.statistics` is removed (Lens no longer forces Hibernate statistics on; use `generateStatistics` in `ormSettings`). The console ORM page is always listed so it can show the integration state
* Defaults: `collect.level` is `light`, `history.trackNonHtml` is off in light and on in full, `collectors.queries.includeParams` is `false` everywhere, `collectors.orm.enabled` is `false` and Lens registers no ORM listener while it is off or bx-orm is not installed. `collectors.bifs` and `collectors.logs` stay off. Light also skips the caller lookup of HTTP calls and transactions and the BoxLang frames of exceptions
* Viewer role: the Logs (list, read, search), Environment, System and Threads pages and routes (including `/api/threads` and the dump) are admin only, and the settings values of `console.access`, `access.proxyPeers` and `console.overridesFile` are hidden from a viewer
* A local only guard accepts only `localhost`, `127.0.0.1` and `[::1]` as Host names unless `access.allowedHosts` is set (DNS rebinding)
* The snapshot JSON of a request is built when first needed (the bar is rendered for an allowed caller, or the console opens the request), not for every request. A request that is not kept in the history skips issue analysis, header capture and the snapshot collectors. The bar insert looks only at the last kilobyte of the page
* Request ids are short counter and time based ids, not random UUIDs
* Reports key URLs with numeric and UUID path segments collapsed (`/orders/:id`); QueryStats keeps SQL without string and number literals; stores trim in batches instead of scanning on every new key
* The module list, JVM facts and cache configuration are cached instead of rebuilt for every request; `LensConfig` resolves paths once
* Hand written scanners replace regular expressions in the query, error, statistics, secret and JSON code, and `Json.write` no longer uses `String.format`
* Test dependency versions are pinned, `mavenLocal` is searched last, and the bundled bx-ai archive is verified by SHA-256

### Added

* Lensy can ask MCP servers (BoxLang+). The AI page lists the eleven Ortus documentation servers (BoxLang, ColdBox, CommandBox, TestBox, WireBox, LogBox, CacheBox, ContentBox, qb, Quick, cbauth), all off, and lets the admin add servers of their own and tick which tools Lensy may use. A server's tools are found with `tools/list` (10 seconds, kept 5 minutes) and named `server.tool` (`server__tool` for the model). Every call goes through the Toolbox: role, enabled and ticked, arguments, limits, redaction and the 12,000 character cap, an `ai.mcp` audit line, and for a custom server your click each time unless it is marked trusted, read only. Addresses are checked on the server (https, no credentials, public addresses only, resolved again before each connection, no redirects). No authentication headers in this version. New routes `ai/mcp`, `ai/mcp/{id}/enable`, `ai/mcp/{id}/test`, audit `ai.mcp.change`, a BoxLang mock of the servers in `harness/mock-mcp`. See [Servers Lensy can ask](docs/console/ai.md#servers-lensy-can-ask-mcp) and the [threat model](docs/security.md#mcp-servers)
* Lensy, the Box Agent for your BoxLang server, has a face: a boxy cube with a lens for an eye in the button, the drawer, the AI page and the bar's Ask link. It looks idle, thinking (a slow blink and a small wiggle, still with reduced motion), happy after an answer and worried after an error. `docs/assets/lensy.svg`
* Lensy (first step of the bx-ai integration, BoxLang+): a chat in a drawer on every console page and on the Ask Lens page, with 32 tools (requests, errors, queries, executors with pool recommendations, threads, blocked threads and deadlocks, GC pressure, datasources, logs, database metadata, a `diagnose` health sweep and a search of the bundled docs) behind one gate (`ops/Toolbox`: role, BoxLang+, read only, arguments, redaction, caps, audit). Actions wait for an Approve click held on the server for five minutes. Streaming answers over server sent events, per session memory in the JVM, `ai.maxConcurrentChats`, `ai.maxToolCalls`, `ai.timeoutSeconds`. The AI page (status, live settings, connection test), the bar's Ask link, `agent/*` and `ai/config` routes, a mock Ollama for tests (`harness/mock-ai.py`). New settings `ai.baseUrl`, `ai.embeddingModel`, `ai.temperature`, `ai.timeoutSeconds`, `ai.maxToolCalls`, `ai.memoryMessages`, `ai.maxConcurrentChats`, `ai.actions`, `ai.rag`, `ai.apiKeyEnv`; the `ai.*` settings except the key are now live. Defaults changed to Ollama, `llama3.2` and `http://localhost:11434`. See [the AI page](docs/console/ai.md)
* Server identity on every record: `util/ServerIdentity` detects the host name, primary address, all addresses and an instance id once at start (refreshed every five minutes, never per request). New settings `server.name`, `server.address`, `server.id` (also `LENS_SERVER_NAME`, `LENS_SERVER_ADDRESS`, `LENS_SERVER_ID`) and `history.serverHeader` (`X-BxLens-Server`). Requests (summary, detail, bar Request tab, console Requests table when more than one server id was seen, exports), error groups and samples, reports, query statistics, in flight entries, the audit log (`server=<id>`), `errors.json` and `reports.json` (top level `server`, `serverId` per record) and the BIFs (`lensServer()`, `server` in `lensReport()` and `lensDiagnostics()`, `serverId` in the rows of `lensErrors()`, `lensQueries()` and `lensInflight()`). `errors.json` is now `{ server, groups }`; the older plain list still loads
* `history.headerAlways` (default `true`): the request id header on every tracked request. The id is also `request.bxlens.id`, `lensRequestId()` and the logging context key `requestId`. `collectors.http.propagateId` (default `false`, needs a core hook that does not exist yet)
* Shared secret-name matcher (adds key, pass, pin, cvv, jwt, auth, session, csrf, webhook, dsn, sk_, cardnumber, x-api-key, accesstoken, clientsecret, trustStorePassword, sslpassword) used by the settings, JVM flags, datasource URLs, task definitions and headers; `-Dname=value` and `--flag=value` pairs are masked inside any value; outgoing HTTP URLs are masked
* Query strings are masked in the snapshot, the history summary and the in-flight list
* Editor link scheme allowlist (server and Settings page), `http(s)` only module links, escaped single quotes in Copy as cURL
* Audit lines for refusals and for log and environment reads; every audit field is sanitized
* Logs: reads from the end of the file, 8 MB scan window, two reads at once (429 when busy)
* ORM integration: Lens listens to bx-orm events only (see Added below). No class of another module is reflected into for SQL, the Hikari metrics tracker is removed at shutdown
* A request older than ten minutes is dropped from the in-flight list

### Fixed

* A viewer sign in no longer clears the admin failure counter; IPv6 clients are counted by /64; the stream no longer refreshes the session and its limit check is atomic
* A proxy header value that is not an IP address is ignored
* The AI call has its own bounded pool and is cancelled on timeout; the AI context carries no URLs, query strings or literals; `console.actions=false` also stops cache clear, evict and reap; the heap dump folder is kept until it is deleted and retried

### Added (earlier in this release)

* ORM support for bx-orm, as the first [integration](docs/reference/integrations.md). Lens listens to the `onORMQuery`, `onORMFlush` and `onORMException` events of bx-orm 1.7.2 or later. Each statement is a query span and a query entry labelled ORM with its kind (select, insert, update, delete, ddl, other), shown in the bar, the request, the Timeline and the console Queries page (free). Startup DDL and failed statements are included; a statement outside a request counts in the ORM totals only; parameter values need `announceQueryParams` in the ORM app and `collectors.queries.includeParams`. The console ORM page (BoxLang+, feature `ormStats`) shows the integration state, per datasource totals from the events (statements by kind, failures, flushes), the recent failures and the Hibernate statistics read through `ORMService.getStatistics`; when statistics are off it says so, and an admin can switch them with `ORMService.setStatisticsEnabled` (audited, refused in read-only mode). Harness: `WITH_ORM=1` installs bx-orm 1.7.2-snapshot (`BX_ORM_VERSION`) or builds it from a checkout (`BX_ORM_DIR`). See [ORM](docs/panels/orm.md)
* Integrations: a small registry (`Integrations`) of module integrations with the status `notInstalled`, `available` or `on`. Every integration is opt in (`collectors.<id>.enabled`, default `false`), needs its module installed and listens to events only. The console Modules page has an Integrations section with a live, admin only switch that is offered only when the module is installed; routes `GET /api/integrations` and `POST /api/integrations/{id}`. Listeners register and unregister without a restart when the setting changes or the module loads (`postModuleLoad`) or unloads (`postModuleUnload`). See [Integrations](docs/reference/integrations.md)
* The Queries console page shows an ORM label and the kind of ORM statements, and counts a failed ORM statement as a failure

## [1.0.0] - 2026-10-08

First release. BX Lens is a commercial Ortus Solutions product. Report issues in the BLMODULES Jira project.

### Added

* Standalone console at `/~bxlens/index.bxm`: password login (`bxsecret:` value), in-memory sessions, CSRF token, per-IP lockout, strict Content-Security-Policy, no external requests. Pages: Overview, Requests, Executors, Tasks, System, Threads, Bar designer, Settings
* Executors page with live health reports and thresholds, Tasks page with status and metrics (Run now, pause, resume and reload need BoxLang+), System and Threads pages with a thread dump download (free)
* Server-Sent Events stream for live console data, with polling as the fallback
* Bar designer: toggle and reorder bar tabs, saved to `config/bxlens-layout.json` (saving needs BoxLang+); Open console button and More menu in the bar
* Request cost (CPU time and bytes allocated, BoxLang+), response headers in the Request panel, slow request sample that names the template and line (BoxLang+), security Notes (`info` severity) for missing headers and cookie flags
* `collect.level` (`off`, `light`, `full`) and `tabs.hide`
* License detection through `bx-plus` (Plus, Trial, Expired, Free), `dev.license` to force a state. See the free and Plus split below
* Phosphor icons (MIT) vendored in place of ad hoc glyphs
* Harness: console password, tasks, `demo-pool`, `load.bxm`, `stall.bxm`, `LENS_LICENSE`
* Rewritten core: typed per request model, bounded in-memory request history, access guard (loopback and private networks by default), server side redaction
* Java collectors for templates, functions, queries (N+1 and slow detection), outgoing HTTP, exceptions, transactions, logs, scopes, runtime, BoxCache statistics and loaded modules
* Alpine.js UI injected at the end of HTML responses: health strip, Issues, unified waterfall with zoom, filters, search and editor links, History for HTML, JSON and SSE requests
* `lensMessage`, `lensDump`, `lensStart`, `lensStop`, `lensMeasure`, `lensAddMeasure`, `lensException`, `lensEnable`, `lensDisable`, `lensIsEnabled`, `lensRender` and `lensPanel` BIFs
* Extension API for applications and modules: `onLensRegister`, `onLensCollect`, `onLensRequestStart`, `onLensRequestFinish`
* Live settings: the console Settings page is editable. Changes apply at once, are saved to `config/bxlens-settings.json` (or `console.overridesFile`), survive restarts and win over `boxlang.json`, with a reset per setting. `SettingsRegistry` defines which settings are live. `console.readOnly` refuses every change from the console
* Roles and access: `console.viewerPassword` for a view-only role (no changes, no download of thread dumps, heap dumps, log files or the bundle, no cache values). `access.trustProxyHeader`, `access.proxyHeader` and `access.proxyPeers` read the client address from a proxy header only from a trusted peer, and never let a remote peer claim loopback. `console.requireHttps` and a warning banner for plain HTTP. An audit log, `bxlens-audit.log`
* Console pages: Datasources (Hikari pool numbers, timings, connection test), Caches (statistics, key list capped at 100, value view cut at 2 KB, evict, reap and clear need BoxLang+), Logs (all files in the logs directory, search, level filter, live tail, path checks, admin download needs BoxLang+), Environment (configuration, modules, JVM arguments, variables, properties, secrets hidden), with a diagnostic bundle zip (BoxLang+), In flight (running requests with a live stack), Queries (per statement runs, average, maximum, total, failures, slow), Errors (grouped by fingerprint, redacted samples), Reports (totals, p50, p95 and p99, status classes, URLs, minute series) and Ask Lens
* System page: Run GC, heap dump (BoxLang+, `console.allowHeapDump`, admin only, confirmation, disk space check, one at a time, removed after download or after 10 minutes) and a deadlock banner that shows the cycle
* Free and BoxLang+ split. Free keeps the bar and most of the console. BoxLang+ or a trial adds request cost and the slow request sample, task actions, cache value, evict, reap and clear, log download, the diagnostic bundle, heap dumps, saving or resetting a bar layout, AI calls from the server, the disk store (`errors.json` and `reports.json` saved atomically, totals since first install, a longer minute series; `store.enabled`, `store.dir`, `store.retentionHours`, `store.maxMB`, `store.flushSeconds`) and a request history longer than 25. A locked item shows a "BoxLang+" note and the server answers 403 (AI answers 409). The split is the current state and may change.
* Optional AI help (`ai.*`): copy a redacted prompt, open ChatGPT or Claude (copies the prompt, sends nothing from the server), and Explain with AI and Ask Lens through the `bx-ai` module. `bx-ai` 3.4.0 ships inside the module in `modules/bxai` and is downloaded by the build (`downloadBxAi`). Checked with a mock Ollama server (`harness/mock-ai.py`). Lens still loads if it is removed
* New settings: `console.viewerPassword`, `console.requireHttps`, `console.readOnly`, `console.overridesFile`, `console.allowHeapDump`, `access.trustProxyHeader`, `access.proxyHeader`, `access.proxyPeers`, `store.*`, `ai.*`, and collectors `datasources`, `caches`, `logfiles`, `environment`, `queries`, `inflight`, `errors`, `reports` and `ask`. `checks.*` and `dev.*` are now declared in the schema
* BIFs that return the console data as plain structs and arrays, in any request: `lensReport()`, `lensErrors( limit=20 )`, `lensQueries( limit=20, sort="slowest" )`, `lensInflight()` and `lensLicense()`. The harness page `api/lens.json.bxm` shows them
* BIFs tab in the bar and the `collectors.bifs` collector: calls, total, average and slowest time and errors per built-in function, from `postBIFInvocation` and `onBIFException`. Heavy and off by default, because every BIF call allocates an event while it is on. Lens's own `lens*` functions are left out, a request keeps up to 300 names and the list shows the top 60 by total time. Needs a BoxLang build with core pull request 657
* Modules page in the console: every loaded module, nested ones included, with version, state, author, activation time, path, public mapping, parent, dependencies and what it provides
* `/~bxlens/` with a trailing slash opens the console (`IndexRewrite.bx`). On MiniServer it also needs a pass predicate, see docs/guides/production.md
* Demo harness app, Playwright end to end suite, documentation site built with bx-sites

### Changed

* **Breaking:** `enabled` is now `bar.enabled`. `access.allowedIPs` and `access.allowPrivateNetworks` are now `bar.access` (use the word `private`). The default is loopback only. See docs/configuration.md
* BX Lens is a product of Ortus Solutions under the BoxLang+ proprietary license (freeware with limits) and no longer described as open source. New Java files carry the four line BoxLang+ header
* The queries collector id and the console Queries page share `collectors.queries.enabled`. The `logs` collector (per request) is separate from `logfiles` (the console Logs page)
* `X-Forwarded-Proto` is believed only from a trusted proxy peer
* Built against BoxLang 1.19, Alpine.js 3.17, Gradle plugins and GitHub Actions updated
* Lens is disabled by default

### Removed

* Events that do not exist in core (`onException`, `onSOAPRequest`, `onSOAPResponse`), the heap and thread dump BIFs, and the monolithic collector

### Fixed

* The module did not compile, never registered its collectors and read event keys core does not send
