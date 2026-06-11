package moe.shizuku.privileged.api;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class ForwardActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent original = getIntent();
        Intent forward = new Intent(original);
        
        // Remove the hardcoded package target
        forward.setPackage(null);
        
        // Target the ShizukuX Manager explicitly
        forward.setClassName("af.shizuku.plus.api", "af.shizuku.manager.authorization.RequestPermissionActivity");
        
        // Retain any extras
        if (original.getExtras() != null) {
            forward.putExtras(original.getExtras());
        }
        
        // Use FLAG_ACTIVITY_FORWARD_RESULT so the result from RequestPermissionActivity goes back to the client app
        forward.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        
        try {
            startActivity(forward);
        } catch (Exception e) {
            // Ignore if ShizukuX is not installed or RequestPermissionActivity is missing
        }
        
        finish();
    }
}
