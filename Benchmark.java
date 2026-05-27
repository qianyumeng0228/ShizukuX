import android.os.ServiceManager;
public class Benchmark {
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        // we can't really benchmark waitForService on Dalvik without a real device/emulator?
        // Let's see what ServiceManager has in the provided android jar.
    }
}
