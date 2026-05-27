cat << 'INNER' > server/src/test/java/rikka/shizuku/server/TestWaitService.java
package rikka.shizuku.server;
import android.os.ServiceManager;
public class TestWaitService {
    public static void test() {
        android.os.IBinder b = ServiceManager.getService("test"); // this exists
    }
}
INNER
./gradlew server:compileDebugJavaWithJavac
