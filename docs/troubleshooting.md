---
title: Troubleshooting
order: 6
description: Fixes for a missing bar and other common problems.
icon: lucide:wrench
---

# Troubleshooting

## The bar does not appear

Check these in order.

1. **Is the bar enabled?** `modules.bxLens.settings.bar.enabled` must be `true`. Restart after you change it. (The old top-level `enabled` is no longer read, see [Configuration](configuration.md#moving-from-older-settings).)
2. **Is the caller allowed?** By default only loopback passes (`bar.access` is `"local"`). Add `"private"`, IPs or CIDR ranges. Remote and Docker hosts often need an entry. If you set `"all"` without `bar.allowAllIPs: true`, Lens falls back to loopback and logs an error. `access.allowedHosts` and `access.requireHeader` apply too. See [Configuration](configuration.md#bar).
3. **Is the response HTML?** The `Content-Type` must start with an entry in `contentTypes`. JSON, SSE, files and redirects get no bar.
4. **Is the path excluded?** Check `excludePaths`.
5. **Did the request end in an uncaught error or abort?** Core renders its own error page and Lens cannot inject into it. Open the next HTML page and check the console Requests page.
6. **Is the response committed?** Lens cannot inject after the response is sent, for example after a `flush`.
7. **Is `inject` false?** Then you must call `lensRender()`.

## The console shows a 404

- Use `/~bxlens/index.bxm`, which always works. `/~bxlens/` with a trailing slash needs the MiniServer pass predicate, see [The console URL](guides/production.md#the-console-url).
- A caller outside `console.access`, `access.allowedHosts` or `access.requireHeader` gets a plain 404 on purpose.
- `console.enabled` must be `true`.

## The console shows a setup page (503)

`console.password` is empty or could not be decrypted. Check the log for `console.password is empty or cannot be decrypted`. A `bxsecret:` value only decrypts with the seed it was made with, so create it with `boxlang generatesecret` on a runtime that uses the same seed.

## I cannot sign in

Wrong passwords are counted per address. After `console.maxLoginAttempts` the address is locked for `console.lockoutMinutes` and the page says how long. A restart signs everyone out. Behind a proxy, all callers may share one address unless Lens reads the client address from the proxy header, so one person's mistakes can lock out the rest. See [Behind a proxy](security.md#behind-a-proxy).

The admin password and the viewer password are different. The one you type decides the role, shown in the console header. The viewer password does nothing when `console.password` is empty.

## Live data does not update

The console uses one Server-Sent Events stream and polls every 3 seconds if it cannot. A proxy that buffers responses can break the stream. Turn buffering off for `/~bxlens/index.bxm/stream` (the response sends `X-Accel-Buffering: no`). `console.maxStreams` caps open streams.

## The console answers 403 "HTTPS is required"

`console.requireHttps` is on and the request came over plain HTTP from a non-loopback address. Use HTTPS. If TLS ends at a proxy, the runtime must see the request as secure. Either it reports HTTPS itself, or the proxy sends `X-Forwarded-Proto: https` and its address is in `access.proxyPeers`. A request from loopback is always allowed.

## A user is blocked, or Lens sees the wrong address

Behind a proxy, Lens sees the proxy's address unless it may read the client address from a header. Check `access.trustProxyHeader`, `access.proxyHeader` and `access.proxyPeers`. The header is only believed when the direct connection comes from a listed peer, and the default `private` covers private networks only. A public proxy address must be listed. A header naming `127.0.0.1` never makes a remote peer a loopback caller. See [Behind a proxy](security.md#behind-a-proxy).

## "The viewer role cannot do this"

You signed in with `console.viewerPassword`. A viewer can look but cannot change anything, download thread dumps, heap dumps, log files or the bundle, or read cache values. Sign in with the admin password. See [Roles](security.md#roles).

## "The console is read-only (console.readOnly)"

`console.readOnly` is `true` in `boxlang.json`. It refuses every change from the console and can only be changed there. Heap dumps, the datasource connection test and AI calls are not stopped by it.

## A setting I changed in boxlang.json has no effect

A change made on the Settings page is saved to `config/bxlens-settings.json` (or `console.overridesFile`) and wins over `boxlang.json`. The setting shows `changed`. Press **Reset** on it, or **Reset all to defaults**, to go back to the `boxlang.json` value. Also check that the file is writable. If it cannot be saved, the page shows "Could not save the overrides".

## A setting says "boxlang.json only"

That setting cannot be changed from the browser. This covers passwords, access rules, `console.*`, `store.*`, `ai.*` and `history.maxRequests`. Edit `boxlang.json` and restart. See [Live settings](configuration.md#live-settings).

## A page is missing in the console

Pages follow the collector switches. `collectors.datasources`, `caches`, `logfiles`, `environment`, `inflight`, `errors`, `reports`, `ask`, `queries`, `executors`, `tasks`, `system` and `threads` each control one page, and `tabs.hide` removes pages by id. Check both, and the Settings page, where collector switches may have been changed and saved.

## Datasources show "timings are not collected"

The pool already has its own Hikari metrics tracker. Lens leaves it alone. The pool numbers still show. A pool that has not started shows `idle` until its first query.

## The Logs page shows no file, or refuses one

Lens lists regular files in the BoxLang logs directory and one level of folders below it. A name outside that directory, or a link that points outside, answers 404. Check the logs directory in your BoxLang logging configuration. Download needs the admin role.

## Errors, Reports or totals are gone after a restart

Without BoxLang+ or a trial, Errors and Reports are in memory only. With one of them, they are saved to `store.dir` (default `lens-data` in the BoxLang home). Check `store.enabled`, that the license state in the console header is Plus or Trial, that the folder is writable, and that the folder is on a volume that survives a restart. Query statistics are never saved. See [Licensing](licensing.md#free-and-boxlang).

## Only 25 requests are in the history

Without BoxLang+ or a trial the history is capped at 25, whatever `history.maxRequests` says. The cap follows the license state after the next license check, which is cached for up to 5 minutes.

## A failing query is missing from Queries

Lens counts a failure when a query started and never finished, or when a database exception with SQL reached the request. A statement that fails to prepare is only seen when the error reaches the request. If your code catches it and does not rethrow, it is not counted. See [Queries](console/in-flight-and-queries.md#how-failures-are-counted).

## The heap dump is refused

- "A heap dump is a BoxLang+ feature": heap dumps need BoxLang+ or a trial.
- "Heap dumps are off": set `console.allowHeapDump` to `true` in `boxlang.json` and restart.
- "Not enough free disk space": the temporary folder needs at least 1.2 times the heap in use free.
- "A heap dump is already running" or "waiting to be downloaded": download or discard the current one.
- "This JVM cannot write heap dumps": the JVM is not HotSpot based.
- A viewer cannot take one.

## AI help does not answer

- "AI is off": set `ai.enabled` to `true`.
- "The bx-ai module is not installed": `bx-ai` ships inside Lens (the 3.6.0 snapshot build), in its `modules/bxai` folder. This message means that folder was removed or did not load. Reinstall the module. Copy prompt and the chat links work without it.
- The server calls need BoxLang+ or a trial. On Free, Explain with AI and Ask are not available, see [Licensing](licensing.md#free-and-boxlang).
- "Too many AI requests": the limit is 10 per minute. "An AI request is already running": wait for it.
- "The model did not answer in time": the call waits 90 seconds. Check that the provider, and for Ollama the server and the model, are reachable.
- "The AI call failed" has the reason from the provider. The log has more.
- Only admins can call the model.

## Lensy button is missing, or it does not answer

The round button, the Alt+K shortcut and the bar's Ask link show only when `bx-ai` is present, you have BoxLang+ or a trial and `ai.enabled` is true. Open the **AI** page (admin) to see which of these fails. Press **Test connection**: it names the provider error. For a local model see [Set up Ollama](guides/ollama.md). A viewer has fewer tools than an admin, so some questions answer "I have no tool for that". "The assistant is busy" means `ai.maxConcurrentChats` chats are running; an answer that stops after `ai.timeoutSeconds` or `ai.maxToolCalls` can be shortened by asking a narrower question. An approval card that says Expired was not answered in five minutes: ask again.

## A button shows BoxLang+ and is disabled

The feature needs BoxLang+ or a trial and the license state in the console header is Free or License expired. The server answers 403 with a message that names BoxLang+ (AI answers 409). See [Licensing](licensing.md#free-and-boxlang) for the list. If you just added a license, wait up to 5 minutes for the next license check.

## Run now or Pause does nothing

Task actions need BoxLang+ or a trial. On Free the Tasks page shows a BoxLang+ note and the server answers 403. Otherwise `console.actions` may be `false`, `console.readOnly` may be `true`, or you may be signed in as a viewer. The buttons are greyed out and the server refuses the call.

## A JSON or ajax request shows nothing

That is expected. Open the console Requests page and find the request. Lens returns its id in the `X-BxLens-Id` header.

## A panel is missing

`functions` and `logs` are off by default. Enable them under `collectors`. At `collect.level: "light"` the `functions`, `logs` and `scopes` collectors are skipped. A tab listed in `tabs.hide`, or a collector that is disabled, hides its tab. A collector that fails logs the error to the `bxLens` logger and is skipped for that request, so check the log.

## Cache numbers for the request look off

Core announces no cache read events, so Lens compares cache statistics from the start and end of the request. Parallel requests can add to the numbers. The Caches page of the console shows the statistics.

## Open in editor does not work

Set `editor.linkPattern` for your editor. When the app runs in a container or on another host, map paths with `editor.remoteBase` and `editor.localBase`.

## Values show as redacted

Keys listed in `redact.keys` are masked on the server. Remove a key from the list only if the value is not sensitive.

## A setting has no effect

Settings live under `modules.bxLens.settings`. Restart the runtime after you change them in `boxlang.json`. The Settings page in the console shows the values Lens is really using, and marks the ones that a saved console change overrides.

## The panel takes extra page weight

Lens inlines its assets, about 110 KB per HTML response. Exclude paths with `excludePaths` or call `lensDisable()` for heavy pages.

## Report a problem

File an issue in the [BLMODULES Jira project](https://ortussolutions.atlassian.net/browse/BLMODULES) or contact [Ortus Solutions support](https://www.ortussolutions.com/services/support). Include your BoxLang version (1.19 or newer), runtime (MiniServer, servlet or CommandBox) and relevant settings (never the console password).
