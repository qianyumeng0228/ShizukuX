package moe.shizuku.privileged.api;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The visible entry point of the compat hub.
 *
 * <p>Without a launcher activity this package has no icon anywhere the user can reach, so once the
 * ShizukuX manager is uninstalled the leftover hub can neither be found nor removed from the app
 * drawer. This screen exists purely to make the package discoverable and to offer the two actions
 * that matter: jump back to the manager, or uninstall this component.
 */
public class HubInfoActivity extends Activity {

    private static final String MANAGER_PACKAGE = "xyz.shizuku.extra.api";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = night ? 0xFF121212 : 0xFFFFFFFF;
        int foreground = night ? 0xFFEDEDED : 0xFF1A1A1A;
        int secondary = night ? 0xFF9E9E9E : 0xFF5F6368;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(background);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.hub_info_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(foreground);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.hub_info_body);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(secondary);
        body.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(16);
        root.addView(body, bodyParams);

        Button openManager = new Button(this);
        openManager.setText(R.string.hub_info_open_manager);
        openManager.setAllCaps(false);
        openManager.setTextColor(Color.WHITE);
        openManager.setBackground(rounded(0xFF6750A4, dp(26)));
        openManager.setStateListAnimator(null);
        openManager.setElevation(0);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        openParams.topMargin = dp(28);
        root.addView(openManager, openParams);
        openManager.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
                if (launch == null) {
                    Toast.makeText(HubInfoActivity.this, R.string.hub_info_manager_missing,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        });

        Button uninstall = new Button(this);
        uninstall.setText(R.string.hub_info_uninstall);
        uninstall.setAllCaps(false);
        uninstall.setTextColor(foreground);
        GradientDrawable outline = new GradientDrawable();
        outline.setShape(GradientDrawable.RECTANGLE);
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(dp(1), night ? 0xFF6E6E6E : 0xFFC7C7C7);
        outline.setCornerRadius(dp(26));
        uninstall.setBackground(outline);
        uninstall.setStateListAnimator(null);
        uninstall.setElevation(0);
        LinearLayout.LayoutParams uninstallParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        uninstallParams.topMargin = dp(12);
        root.addView(uninstall, uninstallParams);
        uninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                finish();
            }
        });

        setContentView(scroll);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
