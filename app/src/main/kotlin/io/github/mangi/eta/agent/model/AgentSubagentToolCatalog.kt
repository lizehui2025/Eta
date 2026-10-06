package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSubagentToolCatalog {
    fun appendTo(tools: JSONArray) {
        val taskProps = JSONObject()
            .put("label", JSONObject().put("type", "string").put("maxLength", AgentSubagentPolicy.MAX_LABEL_CHARS))
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", AgentSubagentPolicy.MAX_PROMPT_CHARS),
            )
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
                        "context_mode",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("pure").put("shared"))
                            .put(
                                "description",
                                "可选；pure（默认）：纯净隔离，只带系统提示+子任务执行；" +
                                    "shared：非纯净共享主 Agent 窗口快照作为前缀上下文，但依旧独立运行 " +
                                    "（独立 controller/loop/transcript，不回写主消息，终态经汇总返回）。需要主对话背景时用 shared。",
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
                description = "并行派生子代理（任务数不限，同时最多跑 4 个，超出的排队；不设轮数与整体超时，只有父任务取消才会终止）：" +
                    "research 模式（默认）只读并发搜集信息；" +
                    "code 模式允许子代理在各自声明的 write_paths 内用 read_file/write_file/edit_file 编辑文件" +
                    "（禁终端与构建，构建与测试由主代理统一执行）。context_mode 可选 pure（默认纯净隔离）或 shared" +
                    "（非纯净：共享主窗口快照但独立运行）。子代理禁 GUI/浏览器/前台操作、敏感写操作与再派生，" +
                    "一次调用内部并行汇总返回，结果完整回填不截断。子代理可以长时间运行，这是正常的：" +
                    "不要为了“防超时”把任务切得过碎或额外派发复核任务。通常只需提供 tasks，mode 与工具集均可省略。",
                parameters = params,
            ),
        )
    }
}
