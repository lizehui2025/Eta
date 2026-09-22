package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** 流首可能出现的 UTF-8 BOM（\uFEFF）；只在流首剥离一次，避免污染首行 "data:"/"event:" 前缀。 */
private const val UTF8_BOM = "\uFEFF"

/** 只处理 SSE 分帧；各协议自行解释事件并在终态返回 false，不等待服务端关闭连接。 */
internal fun readProviderSse(
    stream: InputStream,
    runController: AgentRunController,
    onEvent: (event: String, data: String) -> Boolean,
) {
    var event = ""
    val dataLines = mutableListOf<String>()

    fun dispatch(): Boolean {
        val name = event
        val payload = dataLines.joinToString("\n")
        event = ""
        dataLines.clear()
        if (payload.isBlank()) return true
        runController.throwIfCancelled()
        return onEvent(name, payload)
    }

    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        var atStreamHead = true
        while (true) {
            runController.throwIfCancelled()
            val rawLine = reader.readLine()
            if (rawLine == null) {
                dispatch()
                break
            }
            // 流首剥离 BOM：部分网关会在流首写入 BOM，若保留，首行 "data:"/"event:" 将无法识别。
            val line = if (atStreamHead) {
                atStreamHead = false
                rawLine.removePrefix(UTF8_BOM)
            } else {
                rawLine
            }
            // 兼容“以空白开头再跟 data:/event:”的行：只裁前缀空白后识别字段，不做其它归一化；
            // data 行仍是逐行累积，多行 data 语义不变。
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> if (!dispatch()) break
                trimmed.startsWith("event:") -> event = trimmed.removePrefix("event:").trim()
                trimmed.startsWith("data:") -> dataLines += trimmed.removePrefix("data:").removePrefix(" ")
            }
        }
    }
}
