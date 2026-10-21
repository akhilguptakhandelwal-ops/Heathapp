package com.akhil.framecast;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service, only in the "Full" APK. When it is on, the tool
 * overlay uses it to tap and scroll at the cursor in other apps.
 */
public class ControlService extends AccessibilityService {
    static volatile ControlService instance;

    /** True when this build declares the service (the "Full" APK). */
    static boolean isDeclared(Context c) {
        try {
            c.getPackageManager().getServiceInfo(new ComponentName(c, ControlService.class), 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the service is switched on and connected. */
    static boolean isOn(Context c) {
        if (instance != null) return true;
        if (!isDeclared(c)) return false;
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        String me = new ComponentName(c, ControlService.class).flattenToString();
        String meShort = new ComponentName(c, ControlService.class).flattenToShortString();
        for (String s : on.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        instance = null;
    }
}
