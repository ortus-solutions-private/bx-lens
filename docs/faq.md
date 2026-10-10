---
title: FAQ
order: 7
description: Short answers to common questions.
icon: lucide:circle-help
---

# FAQ

::: expandable "Is Lens safe for production?"
The bar is a development tool, keep it off. The console can run on a live server if you set a `bxsecret:` password, a tight `console.access` list, `console.requireHttps` and `collect.level: "light"`. See [Running Lens in production](guides/production.md).
:::

::: expandable "What is the difference between the bar and the console?"
The bar is a strip on your HTML pages that shows one request. The console is a separate page at `/~bxlens/index.bxm` for one server: requests, errors, queries, datasources, caches, logs, executors, tasks, system and threads. They are switched on independently with `bar.enabled` and `console.enabled`. See [Console](console/index.md).
:::

::: expandable "Why does /~bxlens/ give a 404?"
`/~bxlens/index.bxm` always works. `/~bxlens/` with a trailing slash works only if the request reaches BoxLang. On MiniServer that needs a pass predicate, see [The console URL](guides/production.md#the-console-url). A caller that is not allowed by `console.access` also gets a plain 404 on purpose.
:::

::: expandable "How do I make the console password?"
Run `boxlang generatesecret "your password"` and put the `bxsecret:` value in `console.password`. See [Console](console/index.md#make-the-password).
:::

::: expandable "Is BX Lens open source? Does it need a license?"
No, it is not open source. BX Lens is freeware with limits under a BoxLang+ license. Free keeps the bar and most of the console: the last 25 requests, and errors and reports in memory. BoxLang+ or a trial adds request cost and the slow request sample, task and cache actions, log download, the diagnostic bundle, heap dumps, saving a bar layout, AI calls from the server, the disk store and a request history longer than 25. A locked item shows a "BoxLang+" note. See [Licensing](licensing.md#free-and-boxlang). See [Licensing](licensing.md#free-and-boxlang).
:::

::: expandable "Does the console call out to the internet?"
The page does not. Alpine.js and the Phosphor icons are bundled and fonts are system fonts. A strict Content-Security-Policy blocks any other request, so it works on an air gapped network. The server makes an outside call only if an admin turns on `ai.enabled` with a hosted provider. The Ask ChatGPT and Ask Claude buttons open a new tab in your browser and send nothing from the page.
:::

::: expandable "Does Lens store data?"
Mostly in memory. History keeps the last 50 requests (`history.maxRequests`, 25 without BoxLang+ or a trial), and errors, reports and query statistics live in memory too. They clear on restart or module reload. With BoxLang+ or a trial, errors and reports are also saved to `lens-data` (`store.dir`) so they survive restarts. Lens also writes the bar layout (`config/bxlens-layout.json`), the settings you change in the console (`config/bxlens-settings.json`) and the audit log (`bxlens-audit.log`). There is no database. See [Errors and Reports](console/errors-and-reports.md#the-disk-store).
:::

::: expandable "Can I change settings without a restart?"
Yes, many of them. An admin can change them on the Settings page of the console. The change applies at once and is saved, so it survives a restart and wins over `boxlang.json`. Passwords, access rules and a few more can only be set in `boxlang.json`. See [Live settings](configuration.md#live-settings).
:::

::: expandable "What can a viewer do?"
A viewer signs in with `console.viewerPassword`. They can look at every page. They cannot change anything, and cannot download thread dumps, log files, heap dumps or the diagnostic bundle, or read cache values. The last four need BoxLang+ anyway. See [Roles](security.md#roles).
:::

::: expandable "How do I lock the console to view only?"
Set `console.readOnly` to `true` in `boxlang.json`. Nobody can then change settings, run tasks, clear caches or reset counters from the console. Free already cannot run tasks or clear caches. See [Console settings](console/settings.md#view-only).
:::

::: expandable "Is a heap dump safe?"
A heap dump needs BoxLang+ or a trial. It holds everything in memory, including passwords and session data, and Lens cannot redact it. It is off by default. An admin must turn on `console.allowHeapDump`, confirm the dump, and download the file, which Lens deletes soon after. See [Heap dumps](security.md#heap-dumps).
:::

::: expandable "What does the AI help send, and where?"
Copy prompt and the Ask ChatGPT and Ask Claude buttons send nothing from the server: you paste the prompt yourself. Explain with AI and Ask Lens send a redacted prompt through the `bx-ai` module, only when an admin sets `ai.enabled`. A local provider such as Ollama keeps it inside your network. See [Ask Lens and AI help](console/ask-lens-and-ai.md) and the [data flow](security.md#ai-data-flow).
:::

::: expandable "Does the AI help need bx-ai?"
Only Explain with AI and Ask Lens use it, and only with BoxLang+ or a trial. Copy prompt, Ask ChatGPT and Ask Claude are free. `bx-ai` ships inside Lens (the 3.6.0 snapshot build), so there is nothing to install. If you remove it, Lens still loads.
:::

::: expandable "Why are my errors and reports gone after a restart?"
Without BoxLang+ or a trial they are kept in memory only. With one of them they are saved to disk. See [Licensing](licensing.md#free-and-boxlang).
:::

::: expandable "What is in the diagnostic bundle?"
The diagnostic bundle needs BoxLang+ or a trial. It is a zip with a thread dump, the environment, system, executor, task, datasource and cache data, and the Lens settings, with secrets hidden by name. No request data and no log files. See [Environment](console/environment.md#diagnostic-bundle).
:::

::: expandable "Does it work with APIs and JSON responses?"
Those responses get no bar, but Lens collects and stores them by default. Find them on the console Requests page.
:::

::: expandable "Does it work with ColdBox, Quick or CBWIRE?"
Lens works at the request level, so it sees what core announces. Panels for ColdBox, cbwire and Quick are ideas, not shipped features.
:::

::: expandable "How do I add my own panel?"
Use `lensPanel` in app code or the interception points in a module. See [Extending Lens](guides/extending.md).
:::

::: expandable "Can I turn Lens off for one request?"
Yes. Call `lensDisable()` early in the request.
:::

::: expandable "Why do I see Notes about security headers?"
Lens checks HTML responses for a missing Content-Security-Policy, X-Frame-Options, X-Content-Type-Options, HSTS on HTTPS, and cookie flags. They are Notes: they do not count as issues and do not color the strip. Turn them off with `checks.securityHeaders`. See [Issues](console/issues.md#security-notes).
:::

::: expandable "How is Lens different from BX Insights?"
Lens covers one server and one request at a time. BX Insights is the separate observability product for clusters, history over time and alerting. See the [Roadmap](project/roadmap.md#relation-to-bx-insights).
:::

::: expandable "Why does the bar not show cache hits and misses for a request?"
Core announces no cache read events, so Lens cannot count them per request. The Runtime tab lists the names of the caches and the console Caches page shows the statistics.
:::

::: expandable "Why is there no bar on my error page?"
Core renders its own page for uncaught exceptions and skips the event Lens uses. The request is still on the console Requests page.
:::

::: expandable "Does it need Node or a build step in my app?"
No. The bar inlines its own assets into each HTML response, about 110 KB. The console serves its own files.
:::
