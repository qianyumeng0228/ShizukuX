package af.shizuku.common.util;

import android.os.Process;

public class UserHandleCompat {

    public static final int PER_USER_RANGE = 100000;

    public static int getUserId(int uid) {
        return uid / PER_USER_RANGE;
    }

    public static int getAppId(int uid) {
        return uid % PER_USER_RANGE;
    }

    public static int myUserId() {
        return getUserId(Process.myUid());
    }
}
