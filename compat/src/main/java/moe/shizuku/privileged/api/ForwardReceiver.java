package moe.shizuku.privileged.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ForwardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("rikka.shizuku.intent.action.REQUEST_BINDER".equals(intent.getAction())) {
            Intent forward = new Intent(intent);
            forward.setPackage(null);
            forward.setClassName("af.shizuku.plus.api", "af.shizuku.manager.receiver.BinderRequestReceiver");
            context.sendBroadcast(forward);
        }
    }
}
