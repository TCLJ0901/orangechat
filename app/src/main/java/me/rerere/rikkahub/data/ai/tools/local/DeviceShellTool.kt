/*
 * 垂眸 ChuiMou
 * 设备Shell工具 - 直接使用app进程权限执行shell命令
 * 可访问手机全部文件系统，不受proot沙箱限制
 */

package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_SECONDS = 30L
private const val MAX_TIMEOUT_SECONDS = 300L
private const val MAX_OUTPUT_LENGTH = 64 * 1024 // 64KB

fun deviceShellTool() = Tool(
    name = "device_shell",
    description = """
        Execute a shell command directly on the Android device using the app's process permissions.
        This bypasses the proot sandbox and can access the real file system including /storage/emulated/0,
        /data/data (if permitted), and all mounted paths. Use this for file operations, package management
        queries, device info, and any command that needs real device access.
        Returns stdout, stderr, exit code, and whether the command timed out.
        Timeout default is 30 seconds, max 300 seconds.
    """.trimIndent().replace("\n", " "),
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to execute on the device")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Command timeout in seconds. Default 30, max 300.")
                })
            },
            required = listOf("command"),
        )
    },
    execute = {
        val params = it.jsonObject
        val command = params["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.coerceIn(1L, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val timedOut = !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (timedOut) {
                process.destroyForcibly()
            }

            val stdout = process.inputStream.bufferedReader().use { r -> r.readText() }
            val stderr = process.errorStream.bufferedReader().use { r -> r.readText() }
            val exitCode = if (timedOut) -1 else process.exitValue()

            val truncatedStdout = if (stdout.length > MAX_OUTPUT_LENGTH) {
                stdout.take(MAX_OUTPUT_LENGTH) + "\n...[truncated]"
            } else stdout

            val truncatedStderr = if (stderr.length > MAX_OUTPUT_LENGTH) {
                stderr.take(MAX_OUTPUT_LENGTH) + "\n...[truncated]"
            } else stderr

            val payload = buildJsonObject {
                put("exitCode", exitCode)
                put("stdout", truncatedStdout)
                put("stderr", truncatedStderr)
                put("timedOut", timedOut)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", e.message ?: "Unknown error")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

fun deviceFileReadTool() = Tool(
    name = "device_read_file",
    description = """
        Read a file from the real Android device filesystem (not the proot sandbox).
        Can read files from /storage/emulated/0, app data directories, and any path
        the app has permission to access. Returns file content as text.
        Max file size: 1MB.
    """.trimIndent().replace("\n", " "),
    needsApproval = false,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute file path on the device")
                })
            },
            required = listOf("path"),
        )
    },
    execute = {
        val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        try {
            val file = java.io.File(path)
            if (!file.exists()) error("File not found: $path")
            if (file.length() > 1024 * 1024) error("File too large: ${file.length() / 1024}KB, max 1MB")
            val content = file.readText(Charsets.UTF_8)
            val payload = buildJsonObject {
                put("path", path)
                put("content", content)
                put("size", file.length())
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", e.message ?: "Unknown error")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

fun deviceFileWriteTool() = Tool(
    name = "device_write_file",
    description = """
        Write a file to the real Android device filesystem (not the proot sandbox).
        Can write to /storage/emulated/0 and any path the app has permission to access.
        Parent directories will be created automatically.
    """.trimIndent().replace("\n", " "),
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute file path on the device")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content to write")
                })
            },
            required = listOf("path", "content"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
        try {
            val file = java.io.File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            val payload = buildJsonObject {
                put("success", true)
                put("path", path)
                put("size", file.length())
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", e.message ?: "Unknown error")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

fun deviceListDirTool() = Tool(
    name = "device_list_dir",
    description = """
        List directory contents on the real Android device filesystem.
        Returns file names, sizes, types (file/directory), and last modified times.
    """.trimIndent().replace("\n", " "),
    needsApproval = false,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute directory path on the device")
                })
            },
            required = listOf("path"),
        )
    },
    execute = {
        val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        try {
            val dir = java.io.File(path)
            if (!dir.exists()) error("Directory not found: $path")
            if (!dir.isDirectory) error("Not a directory: $path")
            val files = dir.listFiles() ?: emptyArray()
            val entries = kotlinx.serialization.json.buildJsonArray {
                files.sortedBy { f -> f.name }.take(200).forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("type", if (f.isDirectory) "directory" else "file")
                        put("size", f.length())
                        put("lastModified", f.lastModified())
                    })
                }
            }
            val payload = buildJsonObject {
                put("path", path)
                put("count", files.size)
                put("entries", entries)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", e.message ?: "Unknown error")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)
