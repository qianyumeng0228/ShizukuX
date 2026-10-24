package af.shizuku.manager.plugin

/** In-memory registry of [ExtraFeatureModule]s, populated at app startup. */
object ExtraFeatureRegistry {
    private val modules = mutableListOf<ExtraFeatureModule>()

    @Synchronized
    fun register(module: ExtraFeatureModule) {
        if (modules.none { it.id == module.id }) modules.add(module)
    }
}
