package com.akhil.framecast;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service: owns the on-screen cursor, drawing layer and tool
 * panel, and performs taps / swipes where the cursor is.
 */
public class ControlService extends AccessibilityService {
    static volatile ControlService instance;
    private OverlayController overlay;

    static OverlayController overlay() {
        ControlService s = instance;
        return s == null ? null : s.overlay;
    }

    /** True when the service is switched on and connected. */
    static boolean isOn(Context c) {
        if (instance != null) return true;
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
        overlay = new OverlayController(this);
        instance = this;
        if (RecorderService.running) overlay.show();
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
        if (overlay != null) overlay.hide();
        instance = null;
    }
}
