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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    // --- saved commands (the console itself is one-shot; these are the reusable ones) ---
    binding.consoleSave.setOnClickListener { promptSaveCommand(context, binding) }
    binding.consoleClear.setOnClickListener {
        binding.consoleOutput.text = context.getString(R.string.terminal_console_output_empty)
    }
    renderSavedCommands(context, binding)
}

/** The output box starts with the placeholder line, so the first real output must replace it. */
private fun dropOutputPlaceholder(context: Context, binding: TerminalTutorialActivityBinding) {
    if (binding.consoleOutput.text.toString() == context.getString(R.string.terminal_console_output_empty)) {
        binding.consoleOutput.text = ""
    }
}

private fun renderSavedCommands(context: Context, binding: TerminalTutorialActivityBinding) {
    val group = binding.consoleSaved
    group.removeAllViews()
    val saved = SavedCommands.load(context)
    binding.consoleSavedHint.isVisible = saved.isEmpty()
    saved.forEach { entry -> group.addView(savedChip(context, binding, entry)) }
}

private fun savedChip(
    context: Context,
    binding: TerminalTutorialActivityBinding,
    entry: SavedCommands.Entry
): Chip = Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
    text = entry.name
    isCheckable = false
    isCloseIconVisible = true
    // Tap puts it back in the input instead of running it: a stored command that fires on a
    // mis-tap is a bad trade, and the input is one button press away from running anyway.
    setOnClickListener {
        binding.consoleInput.setText(entry.command)
        binding.consoleInput.setSelection(entry.command.length)
        binding.consoleInput.requestFocus()
    }
    setOnCloseIconClickListener { confirmDeleteCommand(context, binding, entry) }
    setOnLongClickListener {
        promptRenameCommand(context, binding, entry)
        true
    }
}

private fun promptSaveCommand(context: Context, binding: TerminalTutorialActivityBinding) {
    val command = binding.consoleInput.text.toString().trim()
    if (command.isEmpty()) {
        Toast.makeText(context, R.string.terminal_console_empty_command, Toast.LENGTH_SHORT).show()
        return
    }
    val saved = SavedCommands.load(context)
    if (saved.any { it.command == command }) {
        Toast.makeText(context, R.string.terminal_console_saved_duplicate, Toast.LENGTH_SHORT).show()
        return
    }
    if (saved.size >= SavedCommands.MAX) {
        Toast.makeText(context, R.string.terminal_console_saved_full, Toast.LENGTH_SHORT).show()
        return
    }
    val nameInput = EditText(context).apply {
        setText(defaultName(command))
        setSelection(text.length)
        setSingleLine()
        hint = context.getString(R.string.terminal_console_saved_name_hint)
    }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.terminal_console_saved_name_title)
        .setView(paddedDialogView(context, nameInput))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            SavedCommands.add(context, nameInput.text.toString().trim(), command)
            renderSavedCommands(context, binding)
        }
        .show()
}

private fun promptRenameCommand(
    context: Context,
    binding: TerminalTutorialActivityBinding,
    entry: SavedCommands.Entry
) {
    val nameInput = EditText(context).apply {
        setText(entry.name)
        setSelection(text.length)
        setSingleLine()
        hint = context.getString(R.string.terminal_console_saved_name_hint)
    }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.terminal_console_saved_rename_title)
        .setMessage(entry.command)
        .setView(paddedDialogView(context, nameInput))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            SavedCommands.rename(context, entry.command, nameInput.text.toString().trim())
            renderSavedCommands(context, binding)
        }
        .show()
}

private fun confirmDeleteCommand(
    context: Context,
    binding: TerminalTutorialActivityBinding,
    entry: SavedCommands.Entry
) {
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.terminal_console_saved_delete_title)
        .setMessage(entry.command)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.terminal_console_saved_delete) { _, _ ->
            SavedCommands.remove(context, entry.command)
            renderSavedCommands(context, binding)
        }
        .show()
}

/** A name the user can accept as-is: the command itself, shortened when it is long. */
private fun defaultName(command: String): String =
    if (command.length <= 28) command else command.take(26).trimEnd() + "\u2026"

private fun paddedDialogView(context: Context, view: View): View {
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    return FrameLayout(context).apply {
        setPadding(pad, pad / 2, pad, 0)
        addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
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
    dropOutputPlaceholder(context, binding)
    binding.consoleOutput.append("$ $cmd\n")
    CompletableFuture.supplyAsync { runShellOnce(context, cmd) }
        .whenComplete { out, _ ->
            binding.consoleOutput.post {
                binding.consoleOutput.append(out + "\n\n")
                // The command stays in the input: "run, then save" is the normal order, and
                // clearing it left nothing to save right after the one run worth keeping.
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
