/*
 * 垂眸 ChuiMou
 * AI自主决策主动消息时间工具
 */

package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.ProactiveMessageService

/**
 * AI自主决策：设置下次主动消息的触发时间。
 * AI每次被唤醒后，根据当前状态自己决定下次什么时候再来找用户。
 * 这是3.0架构的核心——脚本给事实和工具，AI给决定。
 */
fun setNextProactiveTimeTool(context: Context) = Tool(
    name = "set_next_proactive_time",
    description = """
        Set when to send the next proactive message. Call this at the end of each proactive wake-up
        to decide when to wake up next. You decide the timing based on context:
        - Just chatted? Maybe 60-120 minutes.
        - User sleeping? Set to morning.
        - User busy? Set shorter interval but stay silent.
        - Missing user? Set sooner.
        This overrides the random interval from settings. Only you decide when to come back.
        Returns the scheduled time.
    """.trimIndent().replace("\n", " "),
    needsApproval = false,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("delay_minutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minutes from now until next proactive message. Min 1, max 1440 (24h).")
                })
            },
            required = listOf("delay_minutes"),
        )
    },
    execute = {
        val minutes = it.jsonObject["delay_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: error("delay_minutes is required")
        val clamped = minutes.coerceIn(1, 1440)
        try {
            ProactiveMessageService.scheduleAt(context, clamped)
            val triggerTime = System.currentTimeMillis() + clamped * 60_000L
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val payload = buildJsonObject {
                put("success", true)
                put("delay_minutes", clamped)
                put("next_trigger_time", sdf.format(java.util.Date(triggerTime)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("success", false)
                put("error", e.message ?: "Unknown error")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)
