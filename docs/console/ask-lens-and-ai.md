---
title: Ask Lens and AI help
order: 10
description: Optional help from a language model, with a prompt you can copy or a call through the bx-ai module.
icon: lucide:sparkles
---

# Ask Lens and AI help

Lens can help you read an error, a failing query or a deadlock, and can answer questions about the server. All of it is optional. Nothing is sent anywhere unless you copy a prompt yourself or an admin turns on `ai.enabled`. With BoxLang+ the Ask Lens page is the full page of the [Lensy](ai.md), a chat that looks at the server with tools. The Copy prompt, Ask ChatGPT and Ask Claude buttons stay on the page.

![The Ask Lens page](../assets/screenshots/console-ask.png)

## Three ways to use it

| Way | What happens | Needs |
|---|---|---|
| **Copy prompt** | Lens builds a prompt from redacted data and puts it on your clipboard. You paste it where you like. | Nothing. |
| **Ask ChatGPT** and **Ask Claude** | Lens copies the prompt, then opens `https://chatgpt.com/` or `https://claude.ai/new` in a new tab. You paste the prompt there. The server sends nothing. | Nothing. Turn the buttons off with `ai.links`. |
| **Explain with AI** and **Ask** on the Ask Lens page | The server sends the prompt to a model through the `bx-ai` module and shows the answer. | `ai.enabled`, BoxLang+ or a trial, and an admin. The `bx-ai` module ships inside Lens. |

The buttons appear where they help: on an [error](errors-and-reports.md#errors), on a [statement](in-flight-and-queries.md#queries) and on the [deadlock banner](system-and-threads.md#deadlock) of the Threads page. The Ask Lens page takes a free question of up to 1000 characters.

A viewer can copy a prompt but cannot call the model. Calls from the server need BoxLang+ or a trial. On Free the Ask Lens page says so and the server answers 409. Copy prompt, Ask ChatGPT and Ask Claude stay free.

## What a prompt holds

A prompt holds only data that Lens has already redacted, and it is cut at 8000 characters.

- An error prompt holds the type and message, the request id, the method and the path with numbers and ids replaced (no host and no query string), the status and time, the stack frames, the SQL statement, the last queries and the last messages. Credentials are hidden in all the text, and string and number literals in any SQL are replaced by `?`.
- A query prompt holds the statement with placeholders, the datasource, the run counts and timings, and where it was called from.
- A deadlock prompt holds each thread's name, state, the lock it waits for, who holds it, and up to 14 frames.
- A question prompt holds your question and a short summary of the server: uptime, heap, thread counts, request totals, the latest error types and messages, slow or failing statements (SQL without literals), the number of running requests and the executor summary.

Passwords, parameter values and the configuration are not in a prompt. Credentials inside URLs and secret-looking `key=value` parameters are removed from the whole text. Exception messages are passed through the credential masker and the same literal masking as SQL (every quoted string and number becomes `?`). No URL, host or query string is sent in a server summary. Still read a prompt before you paste it into a service you do not control. See the [data flow](../security.md#ai-data-flow).

## Let the server call a model

`bx-ai` is already inside Lens. Set:

```json title="boxlang.json"
{
	"modules": {
		"bxLens": {
			"settings": {
				"ai": {
					"enabled": true,
					"provider": "ollama",
					"model": "llama3.2",
					"apiKey": ""
				}
			}
		}
	}
}
```

| Setting | Meaning |
|---|---|
| `ai.enabled` | The master switch. Off by default. |
| `ai.provider`, `ai.model`, `ai.baseUrl` | The provider (Ollama by default), the model (`llama3.2`) and the model server (`http://localhost:11434`). |
| `ai.apiKey`, `ai.apiKeyEnv` | The API key as a `bxsecret:` value, or the name of an environment variable that holds it. |
| `ai.links` | Show Copy prompt and the Ask ChatGPT and Ask Claude buttons. |

A local provider such as Ollama keeps the prompt inside your network. A hosted provider receives it. The provider settings (not the key) can be changed from the [AI page](ai.md). They are shared by Explain with AI, Ask Lens and the assistant.

BoxLang AI (`bx-ai`, the 3.6.0 snapshot build) ships inside Lens, in the module's `modules` folder, and is Apache 2.0 licensed. If a server removes it, Lens still loads, **Explain with AI** is hidden and the Ask page says the server is not set to call a model. Lens was checked against `bx-ai` 3.6.0 with a mock Ollama server (`harness/mock-ai.py`).

### Limits on the calls

Explain with AI and the single question button run one AI call at a time, at most 10 per minute, and waits at most 90 seconds for an answer. A call uses a temperature of 0.2. Each call is written to the [audit log](../security.md#audit-log) with its size and provider, not its content. Calls are not blocked by `console.readOnly`, because they change nothing.
