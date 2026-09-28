package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        subagentTools: Boolean = true,
        planTools: Boolean = true,
        /**
         * Capability projection conditions. The [AgentToolCapabilities.full] default only spares
         * catalog-structure tests from restating it; production must pass the real device state
         * captured by `AgentToolCapabilities.capture(context)` (production call sites are
         * AgentModelClient and AgentRuntimeRunExecutor), or the model is offered tools it cannot run.
         */
        capabilities: AgentToolCapabilities = AgentToolCapabilities.full(),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) AgentBrowserToolCatalog.appendTo(tools)
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
            if (planTools) AgentPlanToolCatalog.appendTo(tools)
            if (subagentTools) AgentSubagentToolCatalog.appendTo(tools)
            // Interactive asking is always available: it touches no device capability, and whether to
            // wait for an answer is the run layer's call.
            AgentInteractionToolCatalog.appendTo(tools)
        })
}
