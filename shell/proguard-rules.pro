-keep class rikka.shizuku.shell.ShizukuShellLoader {
    public static void main(java.lang.String[]);
}

# Android 16 eager verifier rejects whole DEXes when hidden-compat bytecode is
# rewritten by R8 9.x without a keep. The shell module independently emits
# rish_shizuku.dex, so keeping only the manager module is not enough
# (upstream ShizukuPlus 6c9da310).
-keep class rikka.hidden.compat.** { *; }

-allowaccessmodification
-repackageclasses
