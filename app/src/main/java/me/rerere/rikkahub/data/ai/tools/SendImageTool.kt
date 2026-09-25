/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.ImageUtils
import java.io.File

fun createSendImageTool(context: Context): Tool {
    return Tool(
        name = "send_image",
        description = "Send a local image file as a visual message in the chat. Use this to show generated images, downloaded pictures, or any local image file to the user. The image will appear inline in the conversation. Provide the absolute file path on the device (e.g. /sdcard/Download/image.png).",
        needsApproval = false,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Absolute file path of the image to send (e.g. /sdcard/Download/image.png)")
                    }
                },
                required = listOf("path")
            )
        },
        execute = { args ->
            val params = args.jsonObject
            try {
                val path = params["path"]?.jsonPrimitive?.content
                    ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "Missing required parameter: path")
                    }.toString()))

                val file = File(path)
                if (!file.exists()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "File not found: $path")
                    }.toString()))
                }

                if (!file.canRead()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "Cannot read file: $path")
                    }.toString()))
                }

                // Compress image for display - limit to 2048px max dimension
                val imageUrl = try {
                    val originalBytes = file.readBytes()
                    val originalBitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                    if (originalBitmap != null) {
                        val compressed = ImageUtils.compressBitmapForAI(originalBitmap, maxSize = 2048, quality = 85)
                        val cacheDir = File(context.filesDir, "sent_images").apply { mkdirs() }
                        val cachedFile = File(cacheDir, "sent_${System.currentTimeMillis()}.jpg")
                        cachedFile.outputStream().use { output ->
                            output.write(compressed)
                        }
                        if (!originalBitmap.isRecycled) {
                            originalBitmap.recycle()
                        }
                        "file://${cachedFile.absolutePath}"
                    } else {
                        // If bitmap decode fails, use original file directly
                        "file://${file.absolutePath}"
                    }
                } catch (e: Exception) {
                    // If compression fails, fall back to original file
                    "file://${file.absolutePath}"
                }

                listOf(
                    UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        put("path", path)
                        put("message", "Image sent successfully.")
                    }.toString()),
                    UIMessagePart.Image(
                        url = imageUrl
                    )
                )
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false)
                    put("error", "Failed to send image: ${e.message}")
                }.toString()))
            }
        }
    )
}
