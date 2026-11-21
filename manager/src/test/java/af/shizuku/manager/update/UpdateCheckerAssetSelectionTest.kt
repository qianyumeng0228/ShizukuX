package af.shizuku.manager.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the update channel to the installed signing identity.
 *
 * The OPPO package shares `moe.shizuku.privileged.api` with the normal Drop-In, so package
 * name alone cannot choose the update asset. These cases are the regression guard against
 * handing a project-signed Drop-In to a testkey install.
 */
class UpdateCheckerAssetSelectionTest {

    private val assets = listOf(
        "ShizukuX-v13.7.0.k2018.apk",
        "ShizukuX-Drop-In-v13.7.0.k2018.apk",
        "ShizukuX-OPPO-Drop-In-v13.7.0.k2018.apk"
    )

    @Test
    fun `oppo testkey build selects oppo asset first`() {
        assertEquals(
            "ShizukuX-OPPO-Drop-In-v13.7.0.k2018.apk",
            UpdateChecker.selectAssetName(assets, isDropIn = true, isOppoTestKey = true)
        )
    }

    @Test
    fun `normal drop-in build never selects oppo asset`() {
        assertEquals(
            "ShizukuX-Drop-In-v13.7.0.k2018.apk",
            UpdateChecker.selectAssetName(assets, isDropIn = true, isOppoTestKey = false)
        )
    }

    @Test
    fun `standard build never selects drop-in or oppo asset`() {
        assertEquals(
            "ShizukuX-v13.7.0.k2018.apk",
            UpdateChecker.selectAssetName(assets, isDropIn = false, isOppoTestKey = false)
        )
    }

    @Test
    fun `oppo testkey build does not fall back to normal drop-in`() {
        assertNull(
            UpdateChecker.selectAssetName(
                listOf(
                    "ShizukuX-v13.7.0.k2018.apk",
                    "ShizukuX-Drop-In-v13.7.0.k2018.apk"
                ),
                isDropIn = true,
                isOppoTestKey = true
            )
        )
    }

    @Test
    fun `legacy oppo testkey filename is still recognized`() {
        assertEquals(
            "ShizukuX-13.7.0.k2018-oppo-testkey.apk",
            UpdateChecker.selectAssetName(
                listOf(
                    "ShizukuX-v13.7.0.k2018.apk",
                    "ShizukuX-13.7.0.k2018-oppo-testkey.apk"
                ),
                isDropIn = true,
                isOppoTestKey = true
            )
        )
    }

    @Test
    fun `drop-in zip name maps to the drop-in asset, not oppo`() {
        assertEquals(
            "ShizukuX-Drop-In-v13.7.0.k2018.apk",
            UpdateChecker.selectAssetName(
                listOf(
                    "ShizukuX-v13.7.0.k2018.apk.zip",
                    "ShizukuX-Drop-In-v13.7.0.k2018.apk",
                    "ShizukuX-OPPO-Drop-In-v13.7.0.k2018.apk.zip"
                ),
                isDropIn = true,
                isOppoTestKey = false
            )
        )
    }
}
