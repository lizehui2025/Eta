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
            AgentCanonicalToolCatalog.appendTo(
                tools = tools,
                browserTools = browserTools,
                terminalTools = terminalTools,
                deviceDirectTools = deviceDirectTools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
                memoryTools = memoryTools,
                memoryWritable = memoryWritable,
                subagentTools = subagentTools,
                planTools = planTools,
            )
            // Personal data and high-risk device controls remain independent so each capability
            // switch and sensitive-data boundary stays narrow in the first compaction pass.
            if (deviceSensitiveReadTools) {
                AgentDeviceToolCatalog.appendIndependentSensitiveReadTools(tools)
            }
            if (deviceSensitiveActionTools) {
                AgentDeviceToolCatalog.appendIndependentSensitiveActionTools(tools)
            }
        })
}
