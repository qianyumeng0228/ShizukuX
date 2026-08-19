package af.shizuku.manager.notifications

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression test for SHIZUKUPLUS-#422 (fixed in 64f552d8).
 *
 * A theme-tinted vector drawable (`android:tint="?attr/..."`) used as a
 * NotificationCompat small icon or action icon crashes with
 * `android.app.RemoteServiceException: Couldn't create icon StatusBarIcon`
 * on some OEM builds, because notification icons are rendered by the
 * system in its own theme context - the app's `?attr/...` reference may
 * not resolve there.
 *
 * This is a static, JVM-only (no Robolectric/Android framework needed)
 * equivalent of scripts/dev/check-notification-icons.sh: it finds every
 * `setSmallIcon(R.drawable.X)` / `.addAction(R.drawable.X, ...)` call site
 * under manager/src/main/java, resolves X's drawable XML (searching this
 * module's res/ as well as sibling res/ dirs such as core/ui, since shared
 * icons like ic_notification_icon live there), and asserts the drawable
 * does not declare `android:tint`.
 *
 * If this test fails, it means a notification icon call site now points at
 * a themed drawable again - swap it for an `ic_notification_*` /
 * untinted variant instead of adding `android:tint` back.
 */
class NotificationIconTintRegressionTest {

    private val iconCallRegex =
        Regex("""\.(?:setSmallIcon|addAction)\(\s*R\.drawable\.([A-Za-z0-9_]+)""")

    private val tintRegex = Regex("""android:tint\s*=\s*"[^"]+"""")

    /** Walk upward from the test's working directory to find the repo root (contains settings.gradle). */
    private fun findRepoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not locate repo root (no settings.gradle found above ${File(".").absolutePath})")
    }

    private fun findDrawable(repoRoot: File, name: String): File? {
        return repoRoot.walkTopDown()
            .firstOrNull { file ->
                file.isFile &&
                    file.name == "$name.xml" &&
                    file.path.contains("${File.separator}res${File.separator}drawable") &&
                    !file.path.contains("${File.separator}build${File.separator}")
            }
    }

    @Test
    fun `notification icon call sites reference only untinted drawables`() {
        val repoRoot = findRepoRoot()
        val managerSrc = File(repoRoot, "manager/src/main/java")
        assertTrue("expected ${managerSrc.path} to exist", managerSrc.exists())

        val kotlinFiles = managerSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find .kt sources under $managerSrc", kotlinFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        var checkedCallSites = 0

        for (file in kotlinFiles) {
            file.readLines().forEachIndexed { index, line ->
                val match = iconCallRegex.find(line) ?: return@forEachIndexed
                val drawableName = match.groupValues[1]
                val drawableFile = findDrawable(repoRoot, drawableName)
                    ?: return@forEachIndexed // resolved elsewhere (e.g. a dependency); nothing to check here

                checkedCallSites++
                if (tintRegex.containsMatchIn(drawableFile.readText())) {
                    violations += "${file.path}:${index + 1} uses R.drawable.$drawableName, " +
                        "which declares android:tint in ${drawableFile.path}"
                }
            }
        }

        // Sanity check: make sure the scan actually walked real call sites, so a refactor that
        // silently breaks the regex/path resolution doesn't make this test vacuously pass.
        assertTrue(
            "expected to find and resolve at least one notification icon call site " +
                "(setSmallIcon/addAction with a local R.drawable.*) - did the code move, or did " +
                "drawable resolution break?",
            checkedCallSites > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "Found notification icon(s) using a theme-tinted drawable - this is exactly the " +
                    "bug fixed in 64f552d8 (RemoteServiceException: Couldn't create icon StatusBarIcon " +
                    "on some OEM builds). Use an untinted ic_notification_* variant instead:\n" +
                    violations.joinToString("\n"),
            )
        }
    }
}
