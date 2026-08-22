# Local / self-hosted LLM support for the AI Coach

Branch: `feat/local-llm-coach`. Adds a `LOCAL_OPENAI_COMPAT` coach provider that points at any
OpenAI-**Chat-Completions**-compatible server the user runs themselves — Ollama, llama.cpp
(`llama-server`), vLLM, SGLang, LM Studio, and anything else speaking the same wire format —
with the API key **optional**.

## 1. What the engines actually implement

Every popular local engine converged on the same de-facto standard: OpenAI's **Chat Completions**
(`POST {base}/v1/chat/completions`) plus `GET {base}/v1/models`. None of them implement the
OpenAI **Responses** API in the form this app speaks natively (Ollama and llama.cpp expose a
`/v1/responses` shim, but it is non-stateful and not universal), so the adapter targets Chat
Completions — exactly like `MiniMaxClient` and `OpenRouterClient` already do.

| Engine | Default base | Auth | `tools` | `response_format` | Notes |
|---|---|---|---|---|---|
| **Ollama** | `http://localhost:11434` | none — key field "required but ignored", dummy `ollama` | yes | yes (JSON mode / schema) | `tool_choice`, `n`, `user`, `logit_bias`, image **URLs** unsupported (base64 images only) |
| **llama.cpp** `llama-server` | `http://127.0.0.1:8080` | none unless `--api-key` | yes, best with `--jinja` | `json_object` **and** `json_schema`; can't combine with `grammar` | `model` field ignored unless `--alias`/router mode |
| **vLLM** | `http://localhost:8000` | none unless `--api-key` / `VLLM_API_KEY` | only with `--enable-auto-tool-choice --tool-call-parser <p>` | yes (xgrammar/guided decoding) | pydantic `extra="allow"` → unknown top-level fields are **warned, not rejected** |
| **SGLang** | `http://localhost:30000` | none unless `--api-key` | yes (`tools`, `tool_choice`, `parallel_tool_calls`) | yes, plus `regex` / `ebnf` | message roles are a strict `Literal` — see §2 |
| **LM Studio** | `http://localhost:1234` | none | yes | `json_schema` only (**no** `json_object`) | also has `/v1/responses` |

Consequence: **assume Chat Completions, assume nothing else.** Everything beyond
`model` / `messages` / `tools` / `response_format` / `max_tokens` has to be opt-in.

## 2. The `role: developer` trap

OpenAI's Responses API (what `CoachOrchestrator` builds) puts the per-turn context in a
`developer` message. Chat Completions predates that role, and the local engines disagree:

- **SGLang** validates roles against a pydantic `Literal`. Current `main` has
  `_GenericMessageRole = Literal["system","assistant","tool","function","developer","latest_reminder"]`
  with a `_normalize_role` validator that **raises** (→ HTTP 400) for anything else. `developer`
  was added later; released versions in the wild reject it outright.
- **vLLM** *rejects* it. Verified against a live vLLM **0.27.1** server:
  `{"role":"developer"}` returns **HTTP 422** — `Failed to deserialize the JSON body into the
  target type: messages[0]: unknown role: developer`. (Older vLLM parsed requests with pydantic
  models set to `extra="allow"` and did accept the role, passing it to the Jinja chat template,
  which usually has no `developer` branch either. Don't rely on the old behaviour.)
- **Ollama / llama.cpp / LM Studio** document only `system` / `user` / `assistant` / `tool`.

Even where the server accepts the role, the *chat template* usually can't render it. So the adapter
**always folds `developer` → `system`**, unconditionally, for every local backend. This is lossless
(the content is instructions either way) and is already what `MiniMaxClient.chatRole` does. Two of
the five engines are confirmed to hard-fail without it, so this is load-bearing, not defensive.

Second, related hazard: many local chat templates require the system message to be **first and
singular** and raise on a system turn after a user turn. `MiniMaxClient` appends
`CoachResponseSchema.promptInstruction` as a *trailing* system message; the local adapter instead
**merges all system messages into one leading system message**, so strict templates render.

## 3. Cleartext HTTP

A LAN box at `http://192.168.1.50:11434` has no TLS. Android blocks cleartext by default
(`usesCleartextTraffic` is false from API 28) and the app currently ships **no**
`network_security_config.xml`, so every local request would fail with `CleartextNotPermitted`
before it left the device.

Network Security Config can't express a CIDR allowlist, so the fix is two-layer:

1. `res/xml/network_security_config.xml` permits cleartext (cloud providers stay HTTPS because
   their endpoints are hardcoded `https://` constants).
2. `LocalEndpoint.validate` refuses a plaintext `http://` URL whose host is **not** loopback,
   RFC1918/CGNAT private, link-local, or `*.local` — the CIDR check the manifest can't do,
   enforced where it can be. `https://` hosts are unrestricted (a self-hosted box with a cert).

## 4. Timeouts

`ResponsesHttp` hardcodes a 60 s read timeout. A 30B model on CPU can spend minutes on one
tool-loop round, so the local provider needs a user-configurable read timeout (default 180 s).
`ResponsesHttp.post` gains an optional `readTimeoutSeconds` that derives a per-call client from the
shared one (`newBuilder()` keeps the connection pool and dispatcher).

The existing retry policy already fits: a stopped local server raises `ConnectException`, which
`isProvablyUnsent` classifies as retryable, and a read timeout is (correctly) not retried.

## 5. Capability toggles, because local ≠ uniform

Three switches in Settings, because the same request body is fatal on one setup and required on
another:

- **Tool calling** (default on). vLLM 400s on `tools` without `--enable-auto-tool-choice`; small
  models hallucinate calls. Off ⇒ the adapter drops `tools` entirely and the coach answers from
  the prompt context alone.
- **Structured output**: `off` (default) / `json_object` / `json_schema`. Off relies on the
  injected `promptInstruction` plus the orchestrator's JSON-repair loop — the same path MiniMax
  uses, and the only one that works everywhere. `json_schema` sends
  `{type:"json_schema", json_schema:{name, strict, schema}}` from `CoachResponseSchema.schema`.
  LM Studio has no `json_object`; some llama.cpp builds error when `json_schema` meets `grammar`.
- **Max output tokens** (blank = omit). Local defaults vary from unlimited to a few hundred.
  Auto-detect fills this in from the server's reported **context window** — see §5b, and note it is
  a derivation, never a copy.

`reasoning` / `reasoning_effort` are **not** sent: only Ollama documents them, and vLLM/SGLang
would warn or 400. Anthropic `cache_control` and OpenRouter's `provider` block are likewise absent.

## 5a. Self-discovery — why the toggles are probed, not looked up

Asking the user to know whether their vLLM was started with `--enable-auto-tool-choice` is a bad
deal, and no metadata endpoint answers it: `/v1/models` describes the *model*, while the two
fields most likely to fail a turn (`tools`, `response_format`) are gated by *launch flags*. So
`LocalCapabilityProbe` sends the fields and reads the answer.

One press of **Detect server & configure** runs:

1. `GET /v1/models` — reachability, the model list, and the sole-model shortcut. This is the only
   step whose failure is fatal; nothing after it can be trusted if the server isn't there.
2. Engine identity, best-effort, from each engine's own info route (first hit wins):
   `GET /version` (vLLM), `/api/version` (Ollama), `/props` (llama.cpp), `/get_server_info`
   (SGLang), `/api/v0/models` (LM Studio). Deliberately *not* `owned_by` from `/v1/models` —
   vLLM says `vllm`, but Ollama says `library` and LM Studio says `organization_owner`, and any
   proxy rewrites all three. Cosmetic only: it drives the summary line, never the request body.
3. A chat request carrying one throwaway tool.
4. A chat request carrying a minimal `response_format: json_schema`; only if that's refused is
   `json_object` tried.

Classification rule: **4xx means the server refused the field** (vLLM answers `400` for a disabled
tool parser and `422` for a field its deserializer doesn't know, so the status itself carries no
extra meaning) → `NO`. A 5xx or a transport failure says nothing about the capability → `UNKNOWN`,
and the setting is **left at its default rather than switched off**, with a note explaining why.
Tool calling in particular only ever turns off on an explicit refusal — an inconclusive probe must
not silently strip the coach of its ability to read the user's data.

Probes 3 and 4 use `max_tokens: 8` and a two-character prompt, and a minimal schema rather than the
coach's own (a large schema risks a rejection *about the schema* being read as "unsupported"). The
timeout is 120 s because on Ollama/LM Studio the first probe also pays for paging the model in.

The probe never picks a model when several are served and none matches the current setting —
guessing would silently move a working setup onto a different model.

### 5b. Max tokens is derived from the context window, never copied from it

Every engine reports the model's context window, under its own name:

| Engine | Route | Field |
|---|---|---|
| vLLM | `/v1/models` | `max_model_len` (262144 on the reference server) |
| llama.cpp | `/v1/models`, `/props` | `n_ctx` as served, `n_ctx_train` as the model ceiling |
| LM Studio | `/api/v0/models` | `loaded_context_length`, `max_context_length` |
| Ollama | `POST /api/show` | `model_info["<arch>.context_length"]` |
| SGLang | `/get_model_info` | context length |

A context window is **prompt + completion**, so writing it straight into `max_tokens` is wrong in
a way that fails closed: the server checks `max_tokens` against what is *left* after the prompt and
rejects a request where the two overflow. The derivation instead reserves room for the prompt:

```
headroom  = context − PROMPT_RESERVE_TOKENS (6144)
suggested = min(headroom, MAX_SUGGESTED_TOKENS (32768))
headroom < 512  ⇒  leave Max tokens blank and warn
```

6144 is the measured coach prompt (3.1–3.3k input tokens for a plain turn on-device) doubled, so a
turn replaying history and feeding back tool results still fits. The 32768 cap keeps a 262k context
from becoming a licence for a runaway generation — a `coach_response` needs far less.

**The warning is the more valuable half.** Ollama ships a default `num_ctx` of **2048**, smaller
than the coach's own prompt: without detection the prompt is silently truncated and the model gets
blamed. Detecting context lets Settings say so, and point at the server-side fix (`num_ctx`,
llama.cpp `-c`, vLLM `--max-model-len`) rather than at a setting in the app.

### Measured on a real server (vLLM 0.27.1, Qwen3.8-27B-INT8)

| Probe | Result |
|---|---|
| `GET /v1/models` | `qwen3.8-27b-int8-w8a16-mtp`, `max_model_len` 262144 |
| `GET /version` | `{"version":"0.27.1"}` → engine identified |
| `tools` | HTTP 200 → supported |
| `response_format: json_schema` | HTTP 200 → supported |
| `max_model_len` | 262144 → Max tokens suggested as 32768 (capped) |
| `role: developer` | **HTTP 422, `unknown role: developer`** |

Also observed: with a reasoning parser enabled, vLLM returns the chain of thought in
`message.reasoning` (older builds: `reasoning_content`) and leaves `content` null until reasoning
finishes. The adapter reads neither field, so this is inert — but it means a `max_tokens` low
enough to truncate mid-reasoning yields no content at all, which the client reports as an
out-of-tokens error rather than a bare "no output".

## 6. Changes in this repo

| File | Change |
|---|---|
| `coach/config/CoachProviderSettings.kt` | new `LOCAL_OPENAI_COMPAT` mode + `local*` fields |
| `coach/config/CoachProviderSettingsStore.kt` | persist base URL, model, optional key, toggles |
| `coach/local/LocalEndpoint.kt` | **new** — URL normalise/validate, private-host rule |
| `coach/local/LocalOpenAICompatClient.kt` | **new** — the Chat Completions adapter |
| `coach/local/LocalModelCatalog.kt` | **new** — `GET /v1/models` for the model dropdown |
| `coach/local/LocalCapabilityProbe.kt` | **new** — engine identity + probed capabilities (§5a) |
| `coach/openai/OpenAIResponsesClient.kt` | `ResponsesHttp`: per-call read timeout + `get` |
| `coach/config/CoachClientResolver.kt` | local branch; readiness sentinel is the **base URL**, not a key |
| `coach/usage/CoachModelPricing.kt` | local models cost $0 |
| `ui/screens/SettingsSubScreens.kt` | local provider section |
| `AndroidManifest.xml`, `res/xml/network_security_config.xml` | cleartext for LAN servers |

### Readiness gate

`CoachClientResolver.Resolution.key` is the sentinel that `PulseLoopApp` ANDs into
`CoachFeatureFlags.coachEnabled`. For local, the key is legitimately absent, so the resolver
returns the **base URL** as the sentinel — blank URL ⇒ coach stays off, key or no key. The client
mirrors that: it throws `ResponsesError.MissingAPIKey` only when the *base URL* is missing, never
for an empty key.

## Sources

- [Ollama — OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [llama.cpp — server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [vLLM — Tool Calling](https://docs.vllm.ai/en/stable/features/tool_calling/)
- [vLLM — `entrypoints/openai/protocol.py`](https://github.com/vllm-project/vllm/blob/v0.11.0/vllm/entrypoints/openai/protocol.py) (`OpenAIBaseModel`, `extra="allow"`)
- [SGLang — `entrypoints/openai/protocol.py`](https://github.com/sgl-project/sglang/blob/main/python/sglang/srt/entrypoints/openai/protocol.py) (`_GenericMessageRole`, `_normalize_role`)
- [SGLang — OpenAI APIs: Completions](https://docs.sglang.io/docs/basic_usage/openai_api_completions)
- [LM Studio — OpenAI compatibility endpoints](https://lmstudio.ai/docs/developer/openai-compat)
