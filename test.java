import android.os.ServiceManager;
public class test {
    public static void main(String[] args) {
        ServiceManager.waitForService("test");
    }
}
