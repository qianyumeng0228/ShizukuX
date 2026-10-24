package af.shizuku.manager.settings

import android.content.Context
import android.content.res.XmlResourceParser
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import org.xmlpull.v1.XmlPullParser

object SettingsSearchEngine {

    data class SettingItem(
        val key: String?,
        val title: String,
        val summary: String?,
        val category: String?,
        val fragmentClass: String,
        val xmlResId: Int
    )

    private var indexedItems: List<SettingItem>? = null

    fun reset() {
        indexedItems = null
    }

    private val screens = mapOf(
        R.xml.settings_shizuku_extra to "af.shizuku.manager.settings.ShizukuExtraSettingsFragment",
        R.xml.settings_personalization to "af.shizuku.manager.settings.PersonalizationSettingsFragment",
        R.xml.settings_behavior to "af.shizuku.manager.settings.BehaviorSettingsFragment",
        R.xml.settings_advanced to "af.shizuku.manager.settings.AdvancedSettingsFragment",
        R.xml.settings_developer_options to "af.shizuku.manager.settings.DeveloperOptionsFragment",
        R.xml.settings_root_integration to "af.shizuku.manager.settings.RootIntegrationSettingsFragment",
        R.xml.settings_app_management to "af.shizuku.manager.settings.AppManagementSettingsFragment",
        R.xml.settings_about to "af.shizuku.manager.settings.AboutSettingsFragment",
        R.xml.settings_device_owner to "af.shizuku.manager.settings.DeviceOwnerSettingsFragment"
    )

    fun init(context: Context) {
        if (indexedItems != null) return
        val items = mutableListOf<SettingItem>()

        val namespace = "http://schemas.android.com/apk/res/android"

        for ((xmlId, fragmentClass) in screens) {
            try {
                val parser = context.resources.getXml(xmlId)
                var eventType = parser.eventType
                var currentCategory: String? = null

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        if (tagName == "PreferenceCategory" ||
                            tagName == "af.shizuku.manager.settings.CollapsiblePreferenceCategory" ||
                            tagName.endsWith("PreferenceCategory")) {

                            val catTitleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                            currentCategory = if (catTitleResId != 0) context.getString(catTitleResId) else parser.getAttributeValue(namespace, "title")
                        } else if (tagName != "PreferenceScreen") {
                            val key = parser.getAttributeValue(namespace, "key")

                            val titleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                            val title = if (titleResId != 0) context.getString(titleResId) else parser.getAttributeValue(namespace, "title")

                            val summaryResId = parser.getAttributeResourceValue(namespace, "summary", 0)
                            val summary = if (summaryResId != 0) context.getString(summaryResId) else parser.getAttributeValue(namespace, "summary")

                            if (!title.isNullOrBlank()) {
                                items.add(SettingItem(key, title, summary, currentCategory, fragmentClass, xmlId))
                            }
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                android.util.Log.w("SettingsSearchEngine", "Failed to index preferences from XML", e)
            }
        }

        // Index the top-level navigation entries of settings_main.xml itself. These are the
        // section-jump entries (Feature Hub, Root Compat, Appearance, App Interactions, Startup
        // Behavior, Advanced/Diagnostics, About) and were previously unsearchable because only the
        // sub-screens in `screens` were walked. Each entry carries android:fragment pointing at
        // the fragment it opens, so tapping a search result navigates exactly like tapping the
        // entry on the main screen.
        try {
            val parser = context.resources.getXml(R.xml.settings_main)
            var eventType = parser.eventType
            var currentCategory: String? = null
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name
                    if (tagName == "PreferenceCategory" ||
                        tagName == "af.shizuku.manager.settings.CollapsiblePreferenceCategory" ||
                        tagName.endsWith("PreferenceCategory")) {
                        val catTitleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                        currentCategory = if (catTitleResId != 0) context.getString(catTitleResId) else parser.getAttributeValue(namespace, "title")
                    } else if (tagName != "PreferenceScreen") {
                        val key = parser.getAttributeValue(namespace, "key")
                        val targetFragment = parser.getAttributeValue(namespace, "fragment")
                        val titleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                        val title = if (titleResId != 0) context.getString(titleResId) else parser.getAttributeValue(namespace, "title")
                        val summaryResId = parser.getAttributeResourceValue(namespace, "summary", 0)
                        val summary = if (summaryResId != 0) context.getString(summaryResId) else parser.getAttributeValue(namespace, "summary")
                        // Navigation entries (with android:fragment) open their target screen from a
                        // search hit, exactly like tapping them. Inline controls placed directly on
                        // the main screen (e.g. the Dhizuku mode card) have no fragment of their own;
                        // index them against the main screen so a search hit opens settings_main and
                        // shows the entry in place.
                        if (!title.isNullOrBlank()) {
                            items.add(SettingItem(key, title, summary, currentCategory, targetFragment ?: "af.shizuku.manager.settings.SettingsFragment", R.xml.settings_main))
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsSearchEngine", "Failed to index settings_main nav entries", e)
        }
        indexedItems = items
    }

    fun search(context: Context, query: String): List<SettingItem> {
        init(context)
        val items = indexedItems ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val devUnlocked = ShizukuSettings.isVectorEnabled()

        return items.filter { item ->
            if (!devUnlocked && item.fragmentClass == "af.shizuku.manager.settings.DeveloperOptionsFragment") return@filter false
            item.title.lowercase().contains(q) ||
            (item.summary != null && item.summary.lowercase().contains(q)) ||
            (item.category != null && item.category.lowercase().contains(q))
        }
    }
}
