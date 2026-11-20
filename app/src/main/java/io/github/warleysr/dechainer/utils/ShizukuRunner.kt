package io.github.warleysr.dechainer.utils

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import timber.log.Timber
import kotlin.concurrent.thread

class ShizukuRunner {
    interface CommandResultListener {
        /** Runs after the command finishes. [done] is always true. */
        fun onCommandResult(output: String, done: Boolean) {}

        /** Runs if the command fails or writes to stderr. */
        fun onCommandError(error: String) {}
    }

    data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        /** Runs [command] as an argv array (never through a shell). Returns null if Shizuku is unavailable. */
        fun exec(vararg command: String): ProcessResult? {
            if (command.isEmpty()) return null
            return try {
                val binder = Shizuku.getBinder()
                if (binder == null || !binder.pingBinder()) return null
                if (Shizuku.isPreV11()) return null
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
                val service = IShizukuService.Stub.asInterface(binder) ?: return null
                val process = service.newProcess(arrayOf(*command), null, null) ?: return null
                try {
                    runCatching { process.outputStream?.close() }
                    var error = ""
                    val errorReader = thread(isDaemon = true, name = "shizuku-stderr") {
                        error = runCatching { ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).bufferedReader().use { it.readText() } }
                            .getOrDefault("")
                    }
                    val output = runCatching { ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).bufferedReader().use { it.readText() } }
                        .getOrDefault("")
                    errorReader.join(10_000)
                    val code = process.waitFor()
                    ProcessResult(code, output.trim(), error.trim())
                } finally {
                    runCatching { process.destroy() }
                }
            } catch (e: Exception) {
                Timber.w(e, "Shizuku exec failed")
                null
            }
        }

        /** Runs a shell line. Only for fixed strings: never interpolate untrusted data. */
        fun command(command: String, listener: CommandResultListener, lineBundle: Int = Integer.MAX_VALUE) {
            val result = exec("sh", "-c", command)
            when {
                result == null -> listener.onCommandError("No Shizuku")
                result.error.isNotBlank() && result.exitCode != 0 -> listener.onCommandError(result.error)
                else -> listener.onCommandResult(result.output, true)
            }
        }
    }
}
