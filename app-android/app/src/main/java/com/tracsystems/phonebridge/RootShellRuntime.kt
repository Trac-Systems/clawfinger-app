package com.tracsystems.phonebridge

import android.os.StrictMode
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit

data class RootCommandResult(
    val ok: Boolean,
    val code: Int,
    val stdout: String,
    val stderr: String,
    val shellPath: String?,
    val error: String? = null,
)

object RootShellRuntime {
    private const val ROOTD_SHELL = "rootd"
    private const val ROOTD_HOST = "127.0.0.1"
    private const val ROOTD_PORT = 48733
    private const val EXIT_MARKER = "__PB_EXIT__:"

    private val shellCandidates = listOf(
        "/data/adb/ap/bin/su",
        "/system/xbin/su",
        "su",
    )

    @Volatile
    private var selectedShell: String? = null

    @Volatile
    private var lastResolveDiagnostics: String? = null

    @Synchronized
    private fun resolveShell(): String? {
        selectedShell?.let { preferred ->
            val preferredProbe = if (preferred == ROOTD_SHELL) {
                executeViaRootDaemon("id", 2_000L)
            } else {
                execute(preferred, "id", 2_000L)
            }
            if (preferredProbe.ok && hasRootIdentity(preferredProbe.stdout)) {
                return preferred
            }
            selectedShell = null
        }
        val diagnostics = mutableListOf<String>()

        val rootdProbe = executeViaRootDaemon("id", 3_000L)
        if (rootdProbe.ok && hasRootIdentity(rootdProbe.stdout)) {
            selectedShell = ROOTD_SHELL
            lastResolveDiagnostics = "resolved:$ROOTD_SHELL"
            return ROOTD_SHELL
        }
        diagnostics += buildString {
            append(ROOTD_SHELL)
            append(":code=")
            append(rootdProbe.code)
            append(":error=")
            append(rootdProbe.error ?: "none")
            if (rootdProbe.stderr.isNotBlank()) {
                append(":stderr=")
                append(rootdProbe.stderr.take(96).replace('\n', ' '))
            }
            if (rootdProbe.stdout.isNotBlank()) {
                append(":stdout=")
                append(rootdProbe.stdout.take(96).replace('\n', ' '))
            }
        }

        shellCandidates.forEach { candidate ->
            val probe = execute(candidate, "id", 3_000L)
            if (probe.ok && hasRootIdentity(probe.stdout)) {
                selectedShell = candidate
                lastResolveDiagnostics = "resolved:$candidate"
                return candidate
            }
            diagnostics += buildString {
                append(candidate)
                append(":code=")
                append(probe.code)
                append(":error=")
                append(probe.error ?: "none")
                if (probe.stderr.isNotBlank()) {
                    append(":stderr=")
                    append(probe.stderr.take(96).replace('\n', ' '))
                }
                if (probe.stdout.isNotBlank()) {
                    append(":stdout=")
                    append(probe.stdout.take(96).replace('\n', ' '))
                }
            }
        }
        lastResolveDiagnostics = diagnostics.joinToString(" | ")
        return null
    }

    fun ensureReady(): RootCommandResult {
        val shell = resolveShell()
            ?: return RootCommandResult(
                ok = false,
                code = 127,
                stdout = "",
                stderr = "",
                shellPath = null,
                error = "no_root_shell:${lastResolveDiagnostics.orEmpty()}",
            )
        val result = if (shell == ROOTD_SHELL) {
            executeViaRootDaemon("id", 3_000L)
        } else {
            execute(shell, "id", 3_000L)
        }
        return if (result.ok && hasRootIdentity(result.stdout)) {
            result
        } else {
            selectedShell = null
            result.copy(ok = false, error = result.error ?: "root_identity_failed")
        }
    }

    fun run(command: String, timeoutMs: Long = 4_000L): RootCommandResult {
        val shell = resolveShell()
            ?: return RootCommandResult(
                ok = false,
                code = 127,
                stdout = "",
                stderr = "",
                shellPath = null,
                error = "no_root_shell",
            )
        if (shell == ROOTD_SHELL) {
            val result = executeViaRootDaemon(command, timeoutMs)
            if (!result.ok) {
                selectedShell = null
            }
            return result
        }
        val result = execute(shell, command, timeoutMs)
        if (!result.ok) {
            selectedShell = null
        }
        return result
    }

    private fun execute(shellPath: String, command: String, timeoutMs: Long): RootCommandResult {
        return runCatching {
            val process = ProcessBuilder(shellPath, "-c", command).start()
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return RootCommandResult(
                    ok = false,
                    code = 124,
                    stdout = "",
                    stderr = "timeout",
                    shellPath = shellPath,
                    error = "timeout",
                )
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
            val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
            val code = process.exitValue()
            RootCommandResult(
                ok = code == 0,
                code = code,
                stdout = stdout,
                stderr = stderr,
                shellPath = shellPath,
                error = if (code == 0) null else "exit_code_$code",
            )
        }.getOrElse { error ->
            RootCommandResult(
                ok = false,
                code = 126,
                stdout = "",
                stderr = error.message.orEmpty(),
                shellPath = shellPath,
                error = error::class.java.simpleName,
            )
        }
    }

    private fun executeViaRootDaemon(command: String, timeoutMs: Long): RootCommandResult {
        return runCatching {
            val previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(previousPolicy)
                    .permitNetwork()
                    .build(),
            )
            try {
                var connectError: Exception? = null
                repeat(4) { attempt ->
                    runCatching {
                        Socket(ROOTD_HOST, ROOTD_PORT).use { socket ->
                            socket.soTimeout = timeoutMs.toInt()
                            val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
                            val payload = "CMD_B64:$encoded\n"
                            val writer = socket.getOutputStream().bufferedWriter()
                            writer.write(payload)
                            writer.flush()
                            socket.shutdownOutput()
                            val response = socket.getInputStream().bufferedReader().use { it.readText() }
                            val markerIndex = response.lastIndexOf(EXIT_MARKER)
                            if (markerIndex < 0) {
                                return RootCommandResult(
                                    ok = false,
                                    code = 126,
                                    stdout = "",
                                    stderr = response.take(256),
                                    shellPath = ROOTD_SHELL,
                                    error = "missing_exit_marker",
                                )
                            }
                            val output = response.substring(0, markerIndex).trimEnd()
                            val codeText = response.substring(markerIndex + EXIT_MARKER.length).trim()
                            val code = codeText.toIntOrNull() ?: 126
                            return RootCommandResult(
                                ok = code == 0,
                                code = code,
                                stdout = output,
                                stderr = if (code == 0) "" else output.take(256),
                                shellPath = ROOTD_SHELL,
                                error = if (code == 0) null else "exit_code_$code",
                            )
                        }
                    }.onFailure { error ->
                        connectError = error as? Exception
                        if (attempt < 3 && error is java.net.ConnectException) {
                            Thread.sleep(120)
                        } else {
                            throw error
                        }
                    }
                }
                throw connectError ?: IllegalStateException("rootd_unavailable")
            } finally {
                StrictMode.setThreadPolicy(previousPolicy)
            }
        }.getOrElse { error ->
            RootCommandResult(
                ok = false,
                code = 126,
                stdout = "",
                stderr = error.message.orEmpty(),
                shellPath = ROOTD_SHELL,
                error = error::class.java.simpleName,
            )
        }
    }

    private fun hasRootIdentity(output: String): Boolean {
        return output.contains("uid=0(") || output.contains("uid=0 ")
    }
}
