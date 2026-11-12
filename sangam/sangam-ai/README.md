# sangam-ai

Provider-neutral AI runtime. Independent implementation from public protocol documents and public product behaviour. Disabled by default: `LlmPort.disabled()` makes no outbound call, and a configured provider still has to pass the tenant's `EgressPolicy`.

**Evidence level:** compiled, with `AiRuntimeTest` run on H2 and the simulator LLM. Not run against a real provider, MCP server, A2A peer or a Tier-1 database.

## Coverage

| NG | File | Status | Stated limits |
|---|---|---|---|
| NG-01 | `draft/DraftService`, `draft/DraftValidators` | Back end done | Validation is structural (BPMN/DMN model + manifest); the REST package validator is a further gate through `Sink` |
| NG-02 | `expr/ExpressionPlayground` | Back end done | FEEL only; JUEL refused because it can call methods. A runaway FEEL evaluation is abandoned on timeout, and its daemon thread may run on |
| NG-03 | `rag/KnowledgeBaseService` | Done, with gap | Brute-force cosine in the JVM, capped by `maxCandidates` (ordered by chunk id, so a cap can drop chunks). No pgvector index. Input is text; PDF/image extraction is not implemented |
| NG-04 | `a2a/A2a` | Done, with gap | `message/send`, `tasks/get`, agent card. No streaming or push. Protocol revision not conformance-tested |
| NG-05 | `mcp/McpClient` | Done, with gap | Streamable HTTP, single-answer. No server-initiated requests. An MCP failure after a non-read-only send parks the run as unknown outcome |
| NG-06 | `mcp/McpServer` | Done, wired | Transport-neutral; served at `POST /api/v1/ai/mcp` through a `RestGateway` (see Server wiring). Tool paths follow `sangam-api.yaml` |
| NG-07 | `agent/AgentDefinition`, `AgentRuntime`, `AgentTools.subAgent` | Done | Sub-agents run as tools inside the run, not as a BPMN ad-hoc sub-process |
| NG-08 | `classify/DocumentClassifier` | Done, with gap | Text only. No OCR. Disabled or failing LLM routes to a human |
| NG-09 | `draft/DraftService` kinds DASHBOARD, TRANSLATION, AGENT_PROMPT | Back end done | Nothing activates; approved content goes to `Sink` |
| NG-10 | `judge/LlmJudge` | Done | Never passes when the LLM is off; verdict marked non-deterministic |
| Correction | `agent/AgentRuntime`, `AgentDefinition.fromRegistryDocument` | Done, wired (`AiWiring`, agents from `sangam.ai.agents-json`) | Agent definitions in the R72 registry now have a runtime with lease fencing, audit and approvals |

## Integration notes

- **Tables:** the `AiSchema` tables (`AI_AGENT_RUN`, `AI_AGENT_STEP`, `AI_AGENT_APPROVAL`, `AI_KB_VERSION`, `AI_KB_CHUNK`, `AI_DRAFT`, `AI_CLASSIFICATION`) are in the persistence generator and its five baselines (`sangam-persistence/tools/generate_changelogs.py`, `sangam-0001-baseline.sql`).
- **Server wiring** (sangam-server `AiWiring`, `AiBindings`; off unless `sangam.modules.ai.enabled=true`):
  - `LlmPort`: `LlmPort.disabled()` unless `sangam.ai.llm.base-uri` and `sangam.ai.llm.model` are set; then `OpenAiCompatibleLlm`, with egress limited to that one origin. `SimulatedLlm` is used by the tests only; the server does not build it.
  - A leader-elected worker (`sangam-ai-worker`) runs `AgentRuntime.runnable` and `step` per tenant, plus a reconcile task that calls `resume`.
  - The tool lookup from `sangam.ai.tools-json`; connector tools use `AgentTools.connector` and need sangam-integration's `ConnectorInvoker`.
  - `McpServer` with a `RestGateway` over the server's own REST base URL, and `A2a.Server` per entry of `sangam.ai.a2a-json` with a `ProcessGateway`. MCP write tools are off unless the tenant is listed in `sangam.ai.mcp.write-tenants`.
  - Draft approval (`POST /api/v1/ai/drafts/{id}/approve`) stores the content as a new package in its draft state and never deploys.
- **REST endpoints:** sangam-rest `AiController` (`/api/v1/ai/runs`, `/ai/approvals`, `/ai/knowledge-bases/**`, `/ai/drafts/**`, `/ai/expressions/{evaluate,propose}`, `/ai/classifications/**`, `/ai/mcp`, `/ai/a2a/{agent}`) and `AiUiController` (the console's `/api/v1/ai/agent-runs`, `/agent-approvals`, `/knowledge-bases`, `/drafts`, `/classifications`).
- **Screens:** `sangam-ui` `src/modules/platform.tsx`, page `/m/ai`: agent runs with their steps, the approval inbox, drafts (generate, approve, reject), knowledge base admin, classification review and a FEEL playground. The modeller's package workspace (`areas/modeller/Modeller.tsx`) has the FEEL playground as a tab (not inside the properties panel) and the bulk test report, which lists judge results apart from deterministic tests. Not built: a diff of a draft against the current content before approval.

## Third-party dependencies

All Apache-2.0 and already in the build: Jackson, `operaton-bpmn-model`, `operaton-dmn-model`, `operaton-engine-feel-scala`. H2 (MPL-2.0 / EPL-1.0) for tests only.
