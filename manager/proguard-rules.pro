-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-assumenosideeffects class java.util.Objects{
    ** requireNonNull(...);
}

-keepnames class af.shizuku.api.BinderContainer

# Missing class android.app.IProcessObserver$Stub
# Missing class android.app.IUidObserver$Stub
-keepclassmembers class rikka.hidden.compat.adapter.ProcessObserverAdapter {
    <methods>;
}

-keepclassmembers class rikka.hidden.compat.adapter.UidObserverAdapter {
    <methods>;
}

# Entrance of Shizuku service
-keep class rikka.shizuku.server.ShizukuService {
    public static void main(java.lang.String[]);
}

# Entrance of user service starter
-keep class af.shizuku.starter.ServiceStarter {
    public static void main(java.lang.String[]);
}

# Entrance of shell
-keep class af.shizuku.manager.shell.Shell {
    public static void main(java.lang.String[], java.lang.String, android.os.IBinder, android.os.Handler);
}

# Keep settings fragments instantiated by name via reflection in PreferenceFragmentCompat
-keep public class af.shizuku.manager.settings.** extends androidx.fragment.app.Fragment {
    public <init>();
}

# Keep WorkManager workers instantiated by name via reflection.
# Both RemoteDbSyncWorker and AdbStartWorker must maintain their class names.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

-assumenosideeffects class af.shizuku.manager.utils.Logger {
    public *** d(...);
}

#noinspection ShrinkerUnresolvedReference
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** d(...);
}

# Mavericks: companion-object factories discovered via Kotlin reflection;
# We must keep both the ViewModel and its Factory/Companion to maintain their relationship.
-keep class af.shizuku.manager.**ViewModel { *; }
-keep class af.shizuku.manager.**ViewModel$* { *; }
-keep class af.shizuku.manager.home.HomeViewModel$Companion { *; }
-keep class * implements com.airbnb.mvrx.MavericksViewModelFactory { *; }
-keep class * extends com.airbnb.mvrx.MavericksViewModel { *; }
-keepclassmembers class * extends com.airbnb.mvrx.MavericksViewModel {
    public static ** Companion;
}

-keep interface com.airbnb.mvrx.** { *; }
-keep class com.airbnb.mvrx.** { *; }
-keepnames class com.airbnb.mvrx.** { *; }

# Keep resource IDs and generated R classes to prevent "0_resource_name_obfuscated" crashes with ViewBinding.
-keep class af.shizuku.manager.R$* { *; }
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Custom View subclasses inflated from XML by class name — R8 must not rename or remove them.
-keep class af.shizuku.manager.utils.EmptyStateView { public <init>(android.content.Context, android.util.AttributeSet); }

# Coroutines and Kotlin serialization
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.coroutines.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Entity
-keep class * implements androidx.room.Dao

-allowaccessmodification
#-repackageclasses rikka.shizuku
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
