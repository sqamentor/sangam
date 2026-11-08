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
| NG-06 | `mcp/McpServer` | Done, needs wiring | Transport-neutral; needs an HTTP endpoint and a `RestGateway`. Tool paths follow `sangam-api.yaml` |
| NG-07 | `agent/AgentDefinition`, `AgentRuntime`, `AgentTools.subAgent` | Done | Sub-agents run as tools inside the run, not as a BPMN ad-hoc sub-process |
| NG-08 | `classify/DocumentClassifier` | Done, with gap | Text only. No OCR. Disabled or failing LLM routes to a human |
| NG-09 | `draft/DraftService` kinds DASHBOARD, TRANSLATION, AGENT_PROMPT | Back end done | Nothing activates; approved content goes to `Sink` |
| NG-10 | `judge/LlmJudge` | Done | Never passes when the LLM is off; verdict marked non-deterministic |
| Correction | `agent/AgentRuntime`, `AgentDefinition.fromRegistryDocument` | Done, needs wiring | Agent definitions in the R72 registry now have a runtime with lease fencing, audit and approvals |

## Integration notes

- **Tables:** `AiSchema` (`AI_AGENT_RUN`, `AI_AGENT_STEP`, `AI_AGENT_APPROVAL`, `AI_KB_VERSION`, `AI_KB_CHUNK`, `AI_DRAFT`, `AI_CLASSIFICATION`) must be added to the persistence generator.
- **Server wiring:**
  - `LlmPort`: default `LlmPort.disabled()`. Only explicit configuration may build `OpenAiCompatibleLlm`, or `SimulatedLlm` on the simulator profile.
  - A worker loop calling `AgentRuntime.runnable` and `step`.
  - The tool lookup function; connector tools use `AgentTools.connector`.
  - `resume` when a reconciliation item closes or a child run ends.
  - HTTP endpoints for `McpServer` and `A2a.Server`, with `RestGateway` and `ProcessGateway` adapters. MCP write tools off by default per tenant.
  - `DraftService.Sink` writing to package **drafts** only.
- **REST endpoints needed:** agent runs and approvals, knowledge base admin, drafts (generate, read, approve, reject), expression evaluate and propose, classification review.
- **Screens needed:**
  - Agent run timeline and approval inbox.
  - Knowledge base admin.
  - Draft review with diff, used by the modeller's "generate" and "edit" actions.
  - FEEL playground in the properties panel.
  - Classification review queue.
  - Judge results listed apart from deterministic tests.

## Third-party dependencies

All Apache-2.0 and already in the build: Jackson, `operaton-bpmn-model`, `operaton-dmn-model`, `operaton-engine-feel-scala`. H2 (MPL-2.0 / EPL-1.0) for tests only.
