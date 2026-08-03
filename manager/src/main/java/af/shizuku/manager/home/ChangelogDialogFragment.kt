package af.shizuku.manager.home

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import af.shizuku.manager.R
import timber.log.Timber

/**
 * Shows what changed in the version the user just updated to. [newInstance] takes the release's
 * raw GitHub release-notes body (fetched by the caller via
 * [af.shizuku.manager.update.UpdateChecker.fetchReleaseNotesForTag]) — this fragment only
 * formats and displays it, so it stays usable even if notes couldn't be fetched (offline).
 */
class ChangelogDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ChangelogDialogFragment"
        private const val ARG_NOTES = "notes"
        private const val ARG_TAG_NAME = "tag_name"

        fun newInstance(notes: String?, tagName: String): ChangelogDialogFragment =
            ChangelogDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTES, notes)
                    putString(ARG_TAG_NAME, tagName)
                }
            }

        /**
         * GitHub release notes are Markdown meant for a web page. For a short dialog, drop the
         * "Recent Releases" rollup table/links (useful on GitHub, noisy here) - the rest is
         * rendered as real Markdown by Markwon below rather than regex-stripped to plain text,
         * so bold/italic/code/list formatting actually shows up instead of literal `**`/`_`/`` ` ``.
         */
        private fun formatForDialog(rawNotes: String): String =
            rawNotes.substringBefore("## 📦 Recent Releases").trim()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val rawNotes = arguments?.getString(ARG_NOTES)
        val tagName = arguments?.getString(ARG_TAG_NAME) ?: ""
        val markwon = Markwon.create(requireContext())

        val message: CharSequence = try {
            rawNotes?.let { formatForDialog(it) }?.takeIf { it.isNotBlank() }
                ?.let { markwon.toMarkdown(it) }
                ?: getString(R.string.changelog_fallback_message)
        } catch (e: Exception) {
            Timber.w(e, "Failed to format release notes for dialog")
            getString(R.string.changelog_fallback_message)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog_title)
            .setMessage(message)
            .setPositiveButton(R.string.changelog_close, null)
            .setNeutralButton(R.string.changelog_view_on_github) { _, _ ->
                try {
                    val url = "https://github.com/thejaustin/ShizukuPlus/releases/tag/$tagName"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to open release page for $tagName")
                }
            }
            .create()

        // Bold/italic/headings/code/lists render from the Spanned message above with no extra
        // work, but a tappable Markdown link needs a movement method on the message TextView -
        // AlertDialog's default one has none, so set it once the view actually exists.
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkMovementMethod.getInstance()
        }

        return dialog
    }
}
