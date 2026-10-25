# Personal Assistant working guide

## Boundaries

- core/AgentRuntime owns only the model/tool loop and its execution budget.
- core/AssistantHarness owns conversation serialization, persistence and lifecycle through explicit aroundRun/reasoning/model/tool boundaries.
- AgentRuntime depends on the concrete AssistantHarness. aroundRun accepts the loop method reference; Harness never injects Runtime.
- AgentExecution holds one committed turn cursor and actual request count. Never add a second mutable transcript.
- Keep lifecycle and loop code as ordinary Java statements. Log in the owning method instead of wrapping stages in generic callbacks.
- Build transient context before each reasoning step. Keep diagnostic logging separate from authoritative checkpoints.
- port/ owns stable component contracts. Use Spring AI ChatModel and ToolCallback directly.
- component/ owns default implementations; extension/ may provide user components.
- config/AssistantAutoConfiguration is the composition root. Defaults yield to user beans.
- Add business tools through VersionedToolCallback beans selected by a release manifest. Do not add business tool names or vendor classes to core.
- Components are selected at startup. Dynamic plugin loading is not implemented.

## State

- ConversationStore is authoritative for chat transcripts.
- MemoryModule owns long-term memory; do not store a second transcript there.
- ContextBuilder selects a model view and never rewrites stored history.
- Preserve assistant/tool call pairing and IDs.
- Persist tool intent before external execution.
- Never automatically replay interrupted side effects.
- Serialize each conversation across restore, model calls, commit and memory update.
- One process owns one local data directory.

## Implementation

Read README.md, docs/ARCHITECTURE.md and docs/COMPONENTS.md first.
Use the smallest implementation that meets a current requirement.
Keep real trust-boundary, permission, persistence and concurrency checks.
Avoid defensive checks for states already ruled out by the supported call chain.
Every non-trivial invariant needs one runnable test.
Update docs/ARCHITECTURE.md and docs/ISSUES.md when behavior changes.
Do not modify the sibling pr-review-agent project as part of assistant work.

## Versioning

- Read docs/VERSIONING.md before changing prompts, component behavior or tool contracts.
- Preserve released prompt resources and release manifests. Behavior changes require a new component version; behavior-preserving internal refactors retain the behavior version and are identified by Git revision and implementation fingerprint.
- Declare component identity on the implementation or Bean factory with ComponentRevision.
- Tool selection must match the exact version in the release; never fall back to latest.
- Freeze the execution manifest when creating a turn and preserve it across checkpoints and terminal states.
- Keep old schema records readable without fabricating current version metadata.
- Custom ConfigurationSnapshot settings must be immutable JSON values without credentials.
- Prefer compilation and the smallest focused checks. Do not run or grow complex suites, browser scenarios, concurrency tests, external-network or paid-model checks unless the user asks.

## Simplicity

- Apply ponytail and redundancy-design-audit when changing code.
- Trace all callers before removing a check or argument.
- Read replies from committed turn checkpoints and component metadata from the manifest; do not create parallel writable sources.
- Prefer existing Spring/JDK APIs over handwritten container traversal and repeated derived collections.
