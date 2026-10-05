package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件路径到模型视觉输入的通用能力，不依赖任何个人数据 Provider。 */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "读取用户指定路径或系统相册 URI 中的一张图片，并作为视觉输入提供给模型。同一轮最多调用一次；需要查看多张图片时，等待当前图片返回并观察后，再在下一轮读取下一张。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "path",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 1_024)
                                    .put("description", "任意绝对图片路径、file URI 或系统相册 content URI；本机路径由 Root 读取"),
                            )
                            .put(
                                "find",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put(
                                        "description",
                                        "可选、默认关闭：图片路径不存在时在工作区按文件名自动查找；" +
                                            "唯一匹配直接采用，多个匹配返回候选列表（不自动采用）。",
                                    ),
                            )
                            .put(
                                "no_fail",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put(
                                        "description",
                                        "可选、默认关闭：路径不存在时只返回候选列表供下一步选择，不自动采用；优先级高于 find。",
                                    ),
                            ),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
