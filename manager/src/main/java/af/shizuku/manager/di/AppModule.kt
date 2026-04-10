package af.shizuku.manager.di

import af.shizuku.manager.utils.ActivityLogManager
import af.shizuku.manager.utils.AppContextManager
import af.shizuku.manager.update.UpdateManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { ActivityLogManager }
    single { AppContextManager }
    single { UpdateManager(androidContext()) }
}
