package com.akhil.framecast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 11, REQ_PERMS = 12;

    private Prefs prefs;
    private PreviewView preview;
    private TextView overlayPill, a11yPill, notifPill, micPill, startBtn, lastTitle, lastSub;
    private View notifRow, lastCard;
    private boolean full;
    private boolean pendingStart, skipTools;
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        dp = Ui.dp(this, 1);
        full = ControlService.isDeclared(this);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * dp);
        col.setPadding(pad, (int) (22 * dp), pad, (int) (28 * dp));
        scroll.addView(col);

        TextView title = text("FrameCast", 30, 800, Ui.TEXT);
        title.setLetterSpacing(-0.02f);
        col.addView(title);
        TextView sub = text("Record your screen inside a phone frame, with a cursor, arrows and boxes in the video.", 14, 450, Ui.MUTED);
        sub.setPadding(0, (int) (4 * dp), 0, (int) (16 * dp));
        col.addView(sub);

        // Preview
        preview = new PreviewView(this, prefs);
        LinearLayout pc = card();
        pc.setPadding(0, 0, 0, 0);
        pc.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (300 * dp)));
        col.addView(pc, cardLp());

        // Start button
        startBtn = text("Start recording", 17, 800, 0xFFFFF6EA);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setPadding(0, (int) (17 * dp), 0, (int) (17 * dp));
        startBtn.setBackground(Ui.round(Ui.VERMILION, 18 * dp));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartClicked();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (14 * dp);
        col.addView(startBtn, sl);

        // Last video
        LinearLayout lc = card();
        lastCard = lc;
        lastTitle = text("", 15, 700, Ui.TEXT);
        lastSub = text("", 13, 450, Ui.MUTED);
        lc.addView(lastTitle);
        lc.addView(lastSub);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, (int) (10 * dp), 0, 0);
        actions.addView(smallButton("Play", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLast(false);
            }
        }));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams((int) (10 * dp), 1));
        actions.addView(smallButton("Share", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLast(true);
            }
        }));
        lc.addView(actions);
        col.addView(lc, cardLp());

        // Setup
        col.addView(section("One-time setup"));
        LinearLayout setup = card();
        overlayPill = pill();
        setup.addView(row("Tools over every screen",
                "Allow FrameCast in \"Display over other apps\". This shows the arrow, square, cursor and tool panel on top of every app.",
                overlayPill, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openOverlaySettings();
                    }
                }));
        a11yPill = pill();
        if (full) setup.addView(row("Real click and scroll",
                "Accessibility → Installed apps → FrameCast cursor and tools → On.\nIf it says \"Restricted setting\": App info → ⋮ (top right) → Allow restricted settings, then turn it on again.",
                a11yPill, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAccessibility();
                    }
                }));
        TextView appInfo = text("Open App info", 13, 700, Ui.AMBER);
        appInfo.setVisibility(full ? View.VISIBLE : View.GONE);
        appInfo.setPadding(0, (int) (2 * dp), 0, (int) (12 * dp));
        appInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppInfo();
            }
        });
        setup.addView(appInfo);
        notifPill = pill();
        notifRow = row("Notification", "While recording, a Stop button also appears in the notification.", notifPill,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askPerms(false);
                    }
                });
        setup.addView(notifRow);
        micPill = pill();
        setup.addView(row("Microphone", "Records your voice with the video (you can turn the mic off below).", micPill,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askPerms(false);
                    }
                }));
        col.addView(setup, cardLp());

        // Options
        col.addView(section("Video"));
        LinearLayout opts = card();
        opts.addView(label("Shape"));
        opts.addView(chips(Prefs.SHAPES, prefs.shape, new Pick() {
            @Override
            public void on(int i) {
                prefs.shape = i;
            }
        }));
        opts.addView(label("Quality"));
        opts.addView(chips(Prefs.QUALITIES, prefs.quality, new Pick() {
            @Override
            public void on(int i) {
                prefs.quality = i;
            }
        }));
        opts.addView(label("Phone colour"));
        opts.addView(chips(Prefs.PHONES, prefs.phone, new Pick() {
            @Override
            public void on(int i) {
                prefs.phone = i;
            }
        }));
        opts.addView(label("Background"));
        opts.addView(chips(Prefs.BACKGROUNDS, prefs.background, new Pick() {
            @Override
            public void on(int i) {
                prefs.background = i;
            }
        }));
        opts.addView(label("Mic"));
        opts.addView(chips(new String[]{"On", "Off"}, prefs.mic ? 0 : 1, new Pick() {
            @Override
            public void on(int i) {
                prefs.mic = i == 0;
            }
        }));
        col.addView(opts, cardLp());

        // How to
        col.addView(section("How to use"));
        LinearLayout how = card();
        String[][] steps = {
                {"Arrow", "Tap Arrow, then drag on the screen. The arrow tip is where you lift your finger."},
                {"Square", "Tap Square, then drag to draw a box."},
                {"Mouse", "Move your thumb and the cursor follows. Lift your finger and the cursor stays there."},
                full ? new String[]{"Click", "Quick tap = click there. Press and hold = only moves the cursor, no click. The ◎ button on the panel clicks at the cursor."}
                        : new String[]{"Click", "Quick tap = shows a click mark in the video. To actually use the app, tap the Mouse button again (tool off) and tap normally. The cursor stays visible."},
                full ? new String[]{"Scroll", "In Mouse mode, drag up / down with two fingers, or use the ⌃ ⌄ buttons on the panel."}
                        : new String[]{"Scroll", "Turn the tool off and scroll normally. Recording keeps going."},
                {"Screenshot", "The camera button saves a picture with the phone frame to Gallery → Pictures/FrameCast."},
                {"Phone normal", "Tap the active tool again to turn it off and use your phone normally."},
                {"Panel", "Drag the timer at the top to move the panel up or down. Tap the timer to shrink or expand the panel."},
                {"Stop", "Tap the red ■ button or Stop in the notification. The video is saved to Gallery → Movies/FrameCast."},
        };
        for (String[] s : steps) {
            TextView h = text(s[0], 14, 700, Ui.AMBER);
            h.setPadding(0, (int) (8 * dp), 0, 0);
            how.addView(h);
            how.addView(text(s[1], 14, 450, Ui.TEXT));
        }
        col.addView(how, cardLp());

        TextView foot = text("The tool panel is kept out of the video. Only your screen, marks and cursor are recorded.", 12, 450, Ui.MUTED);
        foot.setPadding(0, (int) (14 * dp), 0, 0);
        col.addView(foot);
        return scroll;
    }

    private interface Pick {
        void on(int i);
    }

    private TextView text(String s, float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Ui.font(this, weight));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        l.setPadding(p, p, p, p);
        l.setBackground(Ui.round(Ui.CARD, 18 * dp, Math.max(1, (int) dp), Ui.CARD_LINE));
        l.setClipToOutline(true);
        return l;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * dp);
        return lp;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), 12, 700, Ui.MUTED);
        t.setLetterSpacing(0.08f);
        t.setPadding((int) (4 * dp), (int) (22 * dp), 0, (int) (2 * dp));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, 600, Ui.MUTED);
        t.setPadding(0, (int) (10 * dp), 0, (int) (6 * dp));
        return t;
    }

    private TextView pill() {
        TextView t = text("", 13, 700, Ui.INK_DARK);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (14 * dp), (int) (7 * dp), (int) (14 * dp), (int) (7 * dp));
        return t;
    }

    private void setPill(TextView p, boolean ok, String okText, String needText) {
        p.setText(ok ? okText : needText);
        p.setTextColor(ok ? Ui.SAGE : Ui.INK_DARK);
        p.setBackground(ok ? Ui.round(0x1F8FAE7E, 30 * dp, Math.max(1, (int) dp), 0x668FAE7E) : Ui.round(Ui.AMBER, 30 * dp));
    }

    private View row(String title, String desc, TextView pill, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, 15, 700, Ui.TEXT));
        TextView d = text(desc, 13, 450, Ui.MUTED);
        d.setPadding(0, (int) (2 * dp), (int) (10 * dp), 0);
        left.addView(d);
        r.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(pill);
        pill.setOnClickListener(click);
        return r;
    }

    private View chips(String[] options, int selected, final Pick pick) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout l = new LinearLayout(this);
        hs.addView(l);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView c = text(options[i], 14, 600, Ui.TEXT);
            c.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * dp);
            l.addView(c, lp);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.on(idx);
                    prefs.save();
                    styleChips(l, idx);
                    preview.refresh();
                }
            });
        }
        styleChips(l, selected);
        return hs;
    }

    private void styleChips(LinearLayout l, int sel) {
        for (int i = 0; i < l.getChildCount(); i++) {
            TextView c = (TextView) l.getChildAt(i);
            boolean on = i == sel;
            c.setTextColor(on ? Ui.INK_DARK : Ui.TEXT);
            c.setBackground(on ? Ui.round(Ui.AMBER, 30 * dp)
                    : Ui.round(0x00000000, 30 * dp, Math.max(1, (int) dp), 0x55A89D8E));
        }
    }

    private TextView smallButton(String s, View.OnClickListener l) {
        TextView t = text(s, 14, 700, Ui.TEXT);
        t.setPadding((int) (18 * dp), (int) (9 * dp), (int) (18 * dp), (int) (9 * dp));
        t.setBackground(Ui.round(0x00000000, 30 * dp, Math.max(1, (int) dp), 0x88D9A441));
        t.setOnClickListener(l);
        return t;
    }

    private void refresh() {
        setPill(overlayPill, Settings.canDrawOverlays(this), "Allowed", "Allow");
        setPill(a11yPill, ControlService.isOn(this), "On", "Turn on");
        if (Build.VERSION.SDK_INT >= 33) {
            notifRow.setVisibility(View.VISIBLE);
            setPill(notifPill, granted(Manifest.permission.POST_NOTIFICATIONS), "Allowed", "Allow");
        } else {
            notifRow.setVisibility(View.GONE);
        }
        setPill(micPill, granted(Manifest.permission.RECORD_AUDIO), "Allowed", "Allow");

        boolean rec = RecorderService.running;
        startBtn.setText(rec ? "Stop recording" : "Start recording");
        startBtn.setBackground(Ui.round(rec ? 0xFF3A322B : Ui.VERMILION, 18 * dp));

        String uri = Prefs.lastUri(this);
        if (uri == null) {
            lastCard.setVisibility(View.GONE);
        } else {
            lastCard.setVisibility(View.VISIBLE);
            lastTitle.setText("Last video");
            long s = Prefs.lastMs(this) / 1000;
            lastSub.setText(Prefs.lastName(this) + "  ·  " + String.format(Locale.US, "%d:%02d", s / 60, s % 60)
                    + "\nGallery → Movies/FrameCast");
        }
        preview.refresh();
    }

    // ------------------------------------------------------------ actions

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void onStartClicked() {
        if (RecorderService.running) {
            RecorderService r = RecorderService.instance;
            if (r != null) r.requestStop();
            return;
        }
        if (!skipTools && !ControlService.isOn(this) && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Tools are off")
                    .setMessage("To use the arrow, square, cursor and panel, allow FrameCast in \"Display over other apps\".")
                    .setPositiveButton("Open settings", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            openOverlaySettings();
                        }
                    })
                    .setNegativeButton("Record without tools", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            skipTools = true;
                            onStartClicked();
                        }
                    })
                    .show();
            return;
        }
        skipTools = false;
        if (askPerms(true)) return;
        launchCapture();
    }

    /** Requests missing permissions. Returns true if a request is now showing. */
    private boolean askPerms(boolean thenStart) {
        ArrayList<String> need = new ArrayList<>();
        if ((prefs.mic || !thenStart) && !granted(Manifest.permission.RECORD_AUDIO)) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT < 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (need.isEmpty()) return false;
        // A "no" still lets recording go on (just without that feature).
        pendingStart = thenStart;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
        if (code == REQ_PERMS && pendingStart) {
            if (Build.VERSION.SDK_INT < 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                pendingStart = false;
                Toast.makeText(this, "Storage permission is needed to save the video", Toast.LENGTH_LONG).show();
                return;
            }
            launchCapture();
        }
    }

    private void launchCapture() {
        pendingStart = false;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "This phone does not support screen recording", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen recording permission was not given", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_CODE, result)
                .putExtra(RecorderService.EXTRA_DATA, data);
        try {
            startForegroundService(s);
        } catch (Throwable t) {
            Toast.makeText(this, "Could not start recording: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Recording starts in 3 seconds", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            openAppInfo();
        }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            openAppInfo();
        }
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    private void openLast(boolean share) {
        String s = Prefs.lastUri(this);
        if (s == null) return;
        Uri uri = Uri.parse(s);
        if ("file".equals(uri.getScheme())) {
            Toast.makeText(this, "Video saved inside the app: " + uri.getPath(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i;
        if (share) {
            i = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri);
            i = Intent.createChooser(i, "Share video");
        } else {
            i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4");
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "No app found to open the video", Toast.LENGTH_SHORT).show();
        }
    }
}
