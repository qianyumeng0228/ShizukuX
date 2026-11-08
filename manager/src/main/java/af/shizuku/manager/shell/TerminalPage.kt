package af.shizuku.manager.shell

import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import rikka.compatibility.DeviceCompatibility
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The terminal guide's content and interactions, decoupled from its host.
 *
 * Everything here works on an already inflated [TerminalTutorialActivityBinding], so the page can
 * be hosted by [ShellTutorialActivity] (standalone entry) or by the single-container shell without
 * a second copy of the copy-paste strings or the console runner. The only host-specific piece left
 * is the SAF directory picker, injected as [onPickExportDir] because an ActivityResult launcher has
 * to be registered by whoever owns the activity.
 */
fun buildTerminalPage(
    context: Context,
    binding: TerminalTutorialActivityBinding,
    onPickExportDir: () -> Unit
) {
    if (DeviceCompatibility.isMiui()) {
        binding.miui.isVisible = true
    }

    val shName = "<font face=\"monospace\">${TerminalAssets.SH_NAME}</font>"
    val dexName = "<font face=\"monospace\">${TerminalAssets.DEX_NAME}</font>"
    val plusName = "<font face=\"monospace\">${TerminalAssets.PLUS_NAME}</font>"
    val suName = "<font face=\"monospace\">${TerminalAssets.SU_NAME}</font>"

    binding.summary.text = context.getString(R.string.rish_description, shName)
        .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)

    binding.text1.text = context.getString(R.string.terminal_tutorial_1)
    binding.summary1.text =
        context.getString(R.string.terminal_tutorial_export_summary, shName, dexName, plusName, suName)
            .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)

    binding.text2.text = context.getString(R.string.terminal_tutorial_2, shName).toHtml()
    binding.command2.text = context.getString(R.string.terminal_tutorial_copy_command)
    binding.summary2.text =
        context.getString(R.string.terminal_tutorial_copy_summary, shName, plusName, suName).toHtml()

    binding.text3.text = context.getString(R.string.terminal_tutorial_3)

    val rishPath = EnvironmentUtils.resolveExportedPath(TerminalAssets.SH_NAME) ?: "/sdcard/${TerminalAssets.SH_NAME}"
    val plusPath = EnvironmentUtils.resolveExportedPath(TerminalAssets.PLUS_NAME) ?: "/sdcard/${TerminalAssets.PLUS_NAME}"

    val rishCmd = context.getString(R.string.terminal_tutorial_run_command, rishPath)
    val plusCmd = context.getString(R.string.terminal_tutorial_run_plus_command, plusPath)
    binding.command3.text = "$rishCmd\n\n$plusCmd"

    binding.button1.setOnClickListener { onPickExportDir() }
    binding.button2.setOnClickListener { v: View ->
        CustomTabsHelper.launchUrlOrCopy(v.context, Helps.RISH.get())
    }

    // --- in-app shell console (one-shot command runner through Shizuku, uid 2000) ---
    if (!Shizuku.pingBinder()) {
        binding.consoleInput.isEnabled = false
        binding.consoleRun.isEnabled = false
        binding.consoleOutput.text = context.getString(R.string.terminal_console_not_running)
    }
    binding.consoleRun.setOnClickListener { runConsoleCommand(context, binding) }
    binding.consoleInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            runConsoleCommand(context, binding)
            true
        } else {
            false
        }
    }
}

private fun runConsoleCommand(context: Context, binding: TerminalTutorialActivityBinding) {
    if (!Shizuku.pingBinder()) {
        Toast.makeText(context, R.string.terminal_console_not_running, Toast.LENGTH_SHORT).show()
        return
    }
    val cmd = binding.consoleInput.text.toString().trim()
    if (cmd.isEmpty()) {
        Toast.makeText(context, R.string.terminal_console_empty_command, Toast.LENGTH_SHORT).show()
        return
    }
    binding.consoleRun.isEnabled = false
    binding.consoleOutput.append("$ $cmd\n")
    CompletableFuture.supplyAsync { runShellOnce(context, cmd) }
        .whenComplete { out, _ ->
            binding.consoleOutput.post {
                binding.consoleOutput.append(out + "\n\n")
                binding.consoleInput.text?.clear()
                binding.consoleRun.isEnabled = true
            }
        }
}

/**
 * Runs one [cmd] as `sh -c` through a Shizuku shell process and returns its merged output.
 *
 * Mirrors the defensive pattern already used by DiagnosticsExporter.runShell /
 * SceneRelayManager.runShell: stderr is merged into stdout (`2>&1`) so a chatty stderr can never
 * fill the pipe buffer and deadlock a sequential read; the read happens on a worker thread; a
 * command that never finishes is force-destroyed after 10s; and we deliberately never call
 * waitFor()/exitValue() - on some OEM builds (OPPO ColorOS, Android 16) ShizukuProcess.exitValue()
 * throws IllegalArgumentException instead of IllegalThreadStateException, and reading the merged
 * stream to EOF is equivalent.
 */
private fun runShellOnce(context: Context, cmd: String): String {
    return try {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd + " 2>&1"), null, null)
            ?: return context.getString(R.string.terminal_console_process_null)
        val read = CompletableFuture.supplyAsync {
            try {
                p.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Throwable) {
                ""
            }
        }
        try {
            read.get(10, TimeUnit.SECONDS).trim()
        } catch (e: TimeoutException) {
            try { p.destroy() } catch (ignored: Throwable) { /* already dead */ }
            "(timeout after 10s - killed)"
        }
    } catch (e: Throwable) {
        "err:${e.javaClass.simpleName}:${e.message}"
    }
}
