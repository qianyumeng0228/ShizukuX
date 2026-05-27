import android.os.ServiceManager;
public class TestWait {
    public static void main(String[] args) {
        ServiceManager.waitForService("package");
    }
}
