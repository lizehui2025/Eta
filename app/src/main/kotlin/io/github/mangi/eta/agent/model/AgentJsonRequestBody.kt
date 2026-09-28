package io.github.mangi.eta.agent.model

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/**
 * 直接向 OkHttp sink 写 JSON，避免每轮先构造完整请求字符串。
 *
 * 长会话的 transcript / tool schema 可能有数十 MB；`JSONObject.toString()` 会在 UTF-16
 * 字符串和 UTF-8 body 之间多复制一次，并制造一次大对象峰值。这里按 JSON 容器递归写入，
 * 输出内容与 [JSONObject.toString] 保持一致，便于继续复用 Provider 前缀缓存。
 */
internal class AgentJsonRequestBody(
    private val value: JSONObject,
    private val mediaType: MediaType,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun writeTo(sink: BufferedSink) {
        writeValue(sink, value)
    }

    private fun writeValue(sink: BufferedSink, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> sink.writeUtf8("null")
            is JSONObject -> writeObject(sink, value)
            is JSONArray -> writeArray(sink, value)
            is String -> sink.writeUtf8(JSONObject.quote(value))
            is Number -> sink.writeUtf8(JSONObject.numberToString(value))
            is Boolean -> sink.writeUtf8(value.toString())
            else -> sink.writeUtf8(JSONObject.quote(value.toString()))
        }
    }

    private fun writeObject(sink: BufferedSink, value: JSONObject) {
        sink.writeByte('{'.code)
        var first = true
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!first) sink.writeByte(','.code)
            first = false
            sink.writeUtf8(JSONObject.quote(key))
            sink.writeByte(':'.code)
            writeValue(sink, value.opt(key) ?: JSONObject.NULL)
        }
        sink.writeByte('}'.code)
    }

    private fun writeArray(sink: BufferedSink, value: JSONArray) {
        sink.writeByte('['.code)
        for (index in 0 until value.length()) {
            if (index > 0) sink.writeByte(','.code)
            writeValue(sink, value.opt(index) ?: JSONObject.NULL)
        }
        sink.writeByte(']'.code)
    }
}
