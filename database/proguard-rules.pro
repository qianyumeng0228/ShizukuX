# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Room entities
-keep class moe.shizuku.manager.database.ActivityLogRoom { *; }

# Keep DAO
-keep class moe.shizuku.manager.database.ActivityLogDao { *; }

# Keep Database
-keep class moe.shizuku.manager.database.ActivityLogDatabase { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
