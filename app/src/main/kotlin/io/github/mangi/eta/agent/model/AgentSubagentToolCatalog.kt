package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSubagentToolCatalog {
    fun appendTo(tools: JSONArray) {
        val taskProps = JSONObject()
            .put("label", JSONObject().put("type", "string").put("maxLength", 64))
            .put("prompt", JSONObject().put("type", "string").put("maxLength", 4000))
            .put(
                "write_paths",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put(
                        "description",
                        "仅 code 模式生效：该子代理允许写入的文件或目录（绝对路径）。" +
                            "写操作超出声明范围会被拒绝；多个子任务的声明范围不能重叠；research 模式忽略此字段。",
                    ),
            )
        val taskSchema = JSONObject()
            .put("type", "object")
            .put("properties", taskProps)
            .put("required", JSONArray().put("prompt"))
            .put("additionalProperties", false)
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "tasks",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("items", taskSchema),
                    )
                    .put(
                        "mode",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("research").put("code"))
                            .put(
                                "description",
                                "research（默认）：只读并发搜集；code：允许子代理在各自 write_paths 内编辑文件，" +
                                    "禁终端与构建，构建/测试由主代理统一执行。可省略：任务声明 write_paths 或点名写工具时自动按 code 处理。",
                            ),
                    )
                    .put(
                        "max_rounds",
                        JSONObject()
                            .put("type", "integer")
                            .put("minimum", 1)
                            .put(
                                "description",
                                "可选；每个子代理的最大模型轮数，省略时默认 12 轮；传超大值可放开，不设上限。",
                            ),
                    )
                    .put(
                        "timeout_ms",
                        JSONObject()
                            .put("type", "integer")
                            .put("minimum", 1)
                            .put(
                                "description",
                                "可选；整体扇出超时（毫秒），省略时默认 180000（3 分钟）；传超大值可放开，不设上限。",
                            ),
                    )
                    .put(
                        "allowed_tools",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "可省略；仅需收窄子代理工具集时提供，仍受模式白名单过滤；显式空数组等同未提供。"),
                    ),
            )
            .put("required", JSONArray().put("tasks"))
            .put("additionalProperties", false)
        tools.put(
            AgentToolSchema.function(
                name = AgentSubagentPolicy.TOOL_NAME,
                description = "并行派生子代理（任务数不限，同时最多跑 4 个，超出的排队；单任务默认最多 12 轮、整体默认 3 分钟超时）：" +
                    "research 模式（默认）只读并发搜集信息；" +
                    "code 模式允许子代理在各自声明的 write_paths 内用 read_file/write_file/edit_file 编辑文件" +
                    "（禁终端与构建，构建与测试由主代理统一执行）。子代理禁 GUI/浏览器/前台操作、敏感写操作与再派生，" +
                    "一次调用内部并行汇总返回。配置尽量自动化：通常只需提供 tasks，mode、限额与工具集均可省略。",
                parameters = params,
            ),
        )
    }
}
