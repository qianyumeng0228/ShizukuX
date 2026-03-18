# Consumer ProGuard rules for database module
# These rules are included when the module is consumed by other projects

# Keep Room entities
-keep class moe.shizuku.manager.database.ActivityLogRoom { *; }

# Keep DAO
-keep class moe.shizuku.manager.database.ActivityLogDao { *; }

# Keep Database
-keep class moe.shizuku.manager.database.ActivityLogDatabase { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
