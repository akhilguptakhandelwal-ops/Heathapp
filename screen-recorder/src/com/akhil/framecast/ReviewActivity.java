package com.akhil.framecast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Opens after a recording: watch it, cut out any parts (start, middle, end,
 * as many as you like), keep or remove the voice, then Save or Discard.
 * The recording waits in app storage until you decide, so nothing is lost if
 * the app closes; it opens here again next time.
 */
public class ReviewActivity extends Activity {
    private File file;
    private long durationMs;
    private float dp;
    private VideoView video;
    private RangeBar range;
    private TextView times, playBtn, saveBtn, status, voiceKeep, voiceRemove;
    private boolean busy, removeVoice;
    private MediaPlayer player;
    /** Parts removed so far, each {from, to} as fractions 0..1 of the video. */
    private final java.util.ArrayList<float[]> cuts = new java.util.ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Opens the review for the waiting recording, if there is one. */
    static boolean openIfPending(Activity from) {
        String p = Prefs.pendingPath(from);
        if (p == null) return false;
        if (!new File(p).exists()) {
            Prefs.setPending(from, null, 0);
            return false;
        }
        from.startActivity(new Intent(from, ReviewActivity.class));
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = Ui.dp(this, 1);
        String p = Prefs.pendingPath(this);
        if (p == null || !new File(p).exists()) {
            Prefs.setPending(this, null, 0);
            finish();
            return;
        }
        file = new File(p);
        durationMs = Prefs.pendingMs(this);
        setContentView(build());
        video.setVideoPath(file.getAbsolutePath());
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                player = mp;
                applyVolume();
                if (mp.getDuration() > 0) durationMs = mp.getDuration();
                range.setMinGap(Math.min(0.5f, 1000f / Math.max(1000, durationMs)));
                updateTimes();
                video.seekTo(1);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                playBtn.setText("▶  Play");
            }
        });
        updateTimes();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) {
            video.pause();
            playBtn.setText("▶  Play");
        }
    }

    // ------------------------------------------------------------------ UI

    private View build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int pad = (int) (16 * dp);
        col.setPadding(pad, (int) (16 * dp), pad, (int) (16 * dp));

        col.addView(text("Your recording", 24, 800, Ui.TEXT));
        TextView sub = text("Watch it, cut out any parts you don't want, keep or remove the voice, then save.", 13, 450, Ui.MUTED);
        sub.setPadding(0, (int) (2 * dp), 0, (int) (10 * dp));
        col.addView(sub);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(0xFF000000, 14 * dp));
        box.setClipToOutline(true);
        video = new VideoView(this);
        box.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        col.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        playBtn = button("▶  Play", Ui.TEXT, 0x00000000, 0x88D9A441);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        pl.topMargin = (int) (10 * dp);
        col.addView(playBtn, pl);

        TextView trimLabel = text("CUT — select a part with the two handles, tap ✂ Cut. Repeat for more parts.", 11, 700, Ui.MUTED);
        trimLabel.setLetterSpacing(0.06f);
        trimLabel.setPadding(0, (int) (14 * dp), 0, (int) (2 * dp));
        col.addView(trimLabel);
        range = new RangeBar(this);
        range.setListener(new RangeBar.Listener() {
            @Override
            public void changed(float start, float end, int which) {
                updateTimes();
                range.setPlayhead(-1);
                if (video.isPlaying()) {
                    video.pause();
                    playBtn.setText("▶  Play");
                }
                video.seekTo((int) ((which == 0 ? start : end) * durationMs));
            }
        });
        range.set(0.4f, 0.6f);
        range.setCuts(cuts);
        col.addView(range);
        times = text("", 13, 600, Ui.TEXT);
        times.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(times);

        LinearLayout cutRow = new LinearLayout(this);
        cutRow.setGravity(Gravity.CENTER);
        cutRow.setPadding(0, (int) (8 * dp), 0, 0);
        TextView cutBtn = button("✂  Cut selected part", 0xFFFFF6EA, Ui.VERMILION, 0);
        cutBtn.setTextSize(14);
        cutBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cutSelection();
            }
        });
        cutRow.addView(cutBtn);
        View cg = new View(this);
        cutRow.addView(cg, new LinearLayout.LayoutParams((int) (8 * dp), 1));
        TextView undoBtn = button("Undo cut", Ui.TEXT, 0x00000000, 0x88D9A441);
        undoBtn.setTextSize(14);
        undoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoCut();
            }
        });
        cutRow.addView(undoBtn);
        col.addView(cutRow);

        // Voice: keep or remove.
        LinearLayout voice = new LinearLayout(this);
        voice.setGravity(Gravity.CENTER);
        voice.setPadding(0, (int) (12 * dp), 0, 0);
        TextView vl = text("Voice", 13, 700, Ui.MUTED);
        vl.setPadding(0, 0, (int) (10 * dp), 0);
        voice.addView(vl);
        voiceKeep = chip("Keep");
        voiceRemove = chip("Remove");
        voiceKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRemoveVoice(false);
            }
        });
        voiceRemove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRemoveVoice(true);
            }
        });
        voice.addView(voiceKeep);
        View vg = new View(this);
        voice.addView(vg, new LinearLayout.LayoutParams((int) (8 * dp), 1));
        voice.addView(voiceRemove);
        col.addView(voice);
        styleVoice();

        status = text("", 13, 600, Ui.AMBER);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, (int) (6 * dp), 0, 0);
        col.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, (int) (12 * dp), 0, 0);
        TextView discard = button("Discard", Ui.VERMILION, 0x00000000, 0x99E4572E);
        discard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDiscard();
            }
        });
        row.addView(discard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams((int) (10 * dp), 1));
        saveBtn = button("Save", 0xFFFFF6EA, Ui.VERMILION, 0);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        row.addView(saveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        col.addView(row);
        return col;
    }

    private TextView text(String s, float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Ui.font(this, weight));
        return t;
    }

    private TextView button(String s, int fg, int bg, int line) {
        TextView t = text(s, 16, 800, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (20 * dp), (int) (13 * dp), (int) (20 * dp), (int) (13 * dp));
        t.setBackground(line == 0 ? Ui.round(bg, 16 * dp) : Ui.round(bg, 16 * dp, Math.max(1, (int) (1.5f * dp)), line));
        return t;
    }

    private TextView chip(String s) {
        TextView c = text(s, 14, 700, Ui.TEXT);
        c.setPadding((int) (16 * dp), (int) (7 * dp), (int) (16 * dp), (int) (7 * dp));
        return c;
    }

    private void styleVoice() {
        TextView[] t = {voiceKeep, voiceRemove};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == removeVoice;
            int onColor = i == 1 ? Ui.VERMILION : Ui.AMBER;
            t[i].setTextColor(on ? (i == 1 ? 0xFFFFF6EA : Ui.INK_DARK) : Ui.TEXT);
            t[i].setBackground(on ? Ui.round(onColor, 30 * dp)
                    : Ui.round(0x00000000, 30 * dp, Math.max(1, (int) dp), 0x55A89D8E));
        }
    }

    void setRemoveVoice(boolean r) {
        removeVoice = r;
        styleVoice();
        applyVolume();
        updateTimes();
    }

    /** The preview plays muted when the voice will be removed. */
    private void applyVolume() {
        try {
            if (player != null) player.setVolume(removeVoice ? 0f : 1f, removeVoice ? 0f : 1f);
        } catch (Throwable ignored) {
        }
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private boolean edited() {
        return !cuts.isEmpty();
    }

    private long keptMs() {
        float f = 0;
        for (float[] k : Trimmer.keptParts(cuts)) f += k[1] - k[0];
        return Math.round(f * (double) durationMs);
    }

    void cutSelection() {
        float a = range.start(), b = range.end();
        java.util.ArrayList<float[]> test = new java.util.ArrayList<>(cuts);
        test.add(new float[]{a, b});
        float left = 0;
        for (float[] k : Trimmer.keptParts(test)) left += k[1] - k[0];
        if (left * durationMs < 1000) {
            Toast.makeText(this, "Keep at least 1 second of video", Toast.LENGTH_SHORT).show();
            return;
        }
        cuts.add(new float[]{a, b});
        range.setCuts(cuts);
        updateTimes();
    }

    void undoCut() {
        if (cuts.isEmpty()) return;
        cuts.remove(cuts.size() - 1);
        range.setCuts(cuts);
        updateTimes();
    }

    int cutCount() {
        return cuts.size();
    }

    private void updateTimes() {
        long a = (long) (range.start() * durationMs), b = (long) (range.end() * durationMs);
        String t = "Selected " + clock(a) + " – " + clock(b) + "   ·   Final video " + clock(keptMs());
        if (!cuts.isEmpty()) t += "\n" + cuts.size() + (cuts.size() == 1 ? " part cut" : " parts cut");
        times.setText(t);
        String label = edited() ? "Cut & Save" : "Save";
        if (removeVoice) label += " (no voice)";
        saveBtn.setText(label);
    }

    // ------------------------------------------------------------- playback

    /** While playing: jump over cut parts, move the playhead, stop at the end. */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!video.isPlaying()) {
                range.setPlayhead(-1);
                return;
            }
            float f = durationMs > 0 ? video.getCurrentPosition() / (float) durationMs : 0;
            for (float[] k : cuts) {
                float x = Math.min(k[0], k[1]), y = Math.max(k[0], k[1]);
                if (f >= x && f < y) {
                    if (y >= 0.999f) {
                        video.pause();
                        playBtn.setText("▶  Play");
                        range.setPlayhead(-1);
                        return;
                    }
                    video.seekTo((int) (y * durationMs));
                    f = y;
                    break;
                }
            }
            range.setPlayhead(f);
            main.postDelayed(this, 60);
        }
    };

    private void togglePlay() {
        if (busy) return;
        if (video.isPlaying()) {
            video.pause();
            playBtn.setText("▶  Play");
            return;
        }
        if (video.getCurrentPosition() >= durationMs - 300) video.seekTo(0);
        video.start();
        playBtn.setText("❚❚  Pause");
        main.postDelayed(watch, 60);
    }

    // -------------------------------------------------------------- actions

    private void save() {
        if (busy) return;
        busy = true;
        if (video.isPlaying()) video.pause();
        final boolean cut = edited();
        final boolean noVoice = removeVoice;
        float[][] kept = Trimmer.keptParts(cuts);
        final long[][] parts = new long[kept.length][2];
        for (int i = 0; i < kept.length; i++) {
            parts[i][0] = (long) (kept[i][0] * durationMs) * 1000L;
            parts[i][1] = kept[i][1] >= 0.999f ? Long.MAX_VALUE : (long) (kept[i][1] * durationMs) * 1000L;
        }
        final long keptDuration = keptMs();
        status.setText(cut || noVoice ? "Making your video…" : "Saving…");
        saveBtn.setAlpha(0.5f);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                boolean ok = false;
                File out = file;
                try {
                    if (cut || noVoice) {
                        out = new File(file.getParentFile(), "edited_" + file.getName());
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        Trimmer.keep(file.getAbsolutePath(), out.getAbsolutePath(), parts, !noVoice);
                    }
                    String name = "FrameCast_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
                    Uri uri = Saver.save(ReviewActivity.this, out, name);
                    Prefs.setLast(ReviewActivity.this, uri.toString(), name, keptDuration);
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    if (out != file) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                    Prefs.setPending(ReviewActivity.this, null, 0);
                    msg = "Video saved: Gallery → Movies/FrameCast";
                    ok = true;
                } catch (Throwable t) {
                    if (out != file) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                    msg = "Could not save: " + t.getMessage();
                }
                final String m = msg;
                final boolean done = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        saveBtn.setAlpha(1f);
                        Toast.makeText(ReviewActivity.this, m, Toast.LENGTH_LONG).show();
                        if (done) finish();
                        else status.setText(m);
                    }
                });
            }
        }).start();
    }

    private void confirmDiscard() {
        if (busy) return;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Discard this recording?")
                .setMessage("It will be deleted and not saved to your Gallery.")
                .setPositiveButton("Discard", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        video.stopPlayback();
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                        Prefs.setPending(ReviewActivity.this, null, 0);
                        Toast.makeText(ReviewActivity.this, "Recording discarded", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Save this recording?")
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        save();
                    }
                })
                .setNegativeButton("Discard", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        confirmDiscard();
                    }
                })
                .setNeutralButton("Keep editing", null)
                .show();
    }
}
