sed -i 's/import af.shizuku.manager.app.AppActivity/import rikka.material.app.MaterialActivity/' manager/src/main/java/af/shizuku/manager/legacy/ShellRequestHandlerActivity.kt
sed -i 's/AppActivity/MaterialActivity/' manager/src/main/java/af/shizuku/manager/legacy/ShellRequestHandlerActivity.kt
sed -i 's/onReceive/override fun onReceive/' manager/src/main/java/af/shizuku/manager/receiver/BinderRequestReceiver.kt
