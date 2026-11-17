package af.shizuku.x.dhizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Answers at com.rosan.dhizuku.server.provider (the authority Dhizuku-API 2.4 clients
 * hardcode) by forwarding call() to the ShizukuX manager's own Dhizuku provider.
 *
 * <p>Nothing privileged happens here. The bundle that comes back holds the manager's binder,
 * and the client then talks to that binder directly — so Binder.getCallingUid() inside the
 * manager is the real client's uid, and the manager's per-app authorization (database entry
 * plus signature check) still decides what each app may do.
 *
 * <p>ShizukuX's provider reads only the "client" binder from the extras; unlike ShizukuPlus
 * it does not need a forwarded client_uid extra, because authorization happens on the
 * later AIDL calls rather than in call().
 */
public class DhizukuProviderProxy extends ContentProvider {

    private static final String[] MANAGER_AUTHORITIES = {
        "moe.shizuku.privileged.api.dhizuku_server.provider",   // Drop-In flavor
        "xyz.shizuku.extra.api.dhizuku_server.provider",        // Standard flavor
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle forwarded = extras != null ? new Bundle(extras) : new Bundle();
        for (String authority : MANAGER_AUTHORITIES) {
            try {
                Bundle reply =
                        getContext()
                                .getContentResolver()
                                .call(Uri.parse("content://" + authority), method, arg, forwarded);
                if (reply != null) return reply;
            } catch (Exception ignored) {
                // Not installed (or not that flavor); try the next one.
            }
        }
        return null;
    }

    // Dhizuku only uses call(); stubs satisfy ContentProvider's abstract contract.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
