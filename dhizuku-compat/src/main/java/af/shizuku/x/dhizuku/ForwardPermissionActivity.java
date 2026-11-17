package af.shizuku.x.dhizuku;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Turns Dhizuku-API 2.4's com.rosan.dhizuku.action.request.permission into the ShizukuX
 * manager's REQUEST_DHIZUKU_PERMISSION, which shows the Allow/Deny prompt and replies to the
 * client's listener binder (carried in the extras, nested under "bundle" by 2.4).
 *
 * <p>Because the forward changes the launching uid, the manager only honours this when the
 * launcher is signed with its own certificate; see the forwarder check in
 * RequestPermissionActivity of the manager.
 */
public class ForwardPermissionActivity extends Activity {

    private static final String[] MANAGER_PACKAGES = {
        "moe.shizuku.privileged.api",
        "xyz.shizuku.extra.api",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();
        for (String pkg : MANAGER_PACKAGES) {
            Intent forward = new Intent(pkg + ".action.REQUEST_DHIZUKU_PERMISSION")
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (extras != null) forward.putExtras(extras);
            try {
                startActivity(forward);
                break;
            } catch (Exception ignored) {
                // That manager isn't installed; try the next one.
            }
        }
        // Theme.NoDisplay activities must finish before onResume.
        finish();
    }
}
