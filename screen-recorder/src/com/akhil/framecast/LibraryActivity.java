package com.akhil.framecast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "My recordings": FrameCast videos and screenshots, with Play, Share and Delete. */
public class LibraryActivity extends Activity {
    private static final int REQ_READ = 21, REQ_DELETE = 22;

    static final class Item {
        Uri uri;
        String name;
        long date, size, duration;
        boolean video;
    }

    private float dp;
    private boolean showVideos = true;
    private LinearLayout list;
    private TextView tabVideos, tabShots, empty;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = Ui.dp(this, 1);
        setContentView(build());
        askReadPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * dp);
        col.setPadding(pad, (int) (18 * dp), pad, (int) (28 * dp));
        scroll.addView(col);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, 700, Ui.TEXT);
        back.setPadding(0, 0, (int) (14 * dp), (int) (4 * dp));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back);
        top.addView(text("My recordings", 26, 800, Ui.TEXT));
        col.addView(top);
        TextView sub = text("Saved in Gallery → Movies/FrameCast and Pictures/FrameCast", 13, 450, Ui.MUTED);
        sub.setPadding(0, (int) (2 * dp), 0, (int) (14 * dp));
        col.addView(sub);

        LinearLayout tabs = new LinearLayout(this);
        tabVideos = chip("Videos");
        tabShots = chip("Screenshots");
        tabVideos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVideos = true;
                reload();
            }
        });
        tabShots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVideos = false;
                reload();
            }
        });
        tabs.addView(tabVideos);
        View gap = new View(this);
        tabs.addView(gap, new LinearLayout.LayoutParams((int) (8 * dp), 1));
        tabs.addView(tabShots);
        col.addView(tabs);

        empty = text("", 14, 450, Ui.MUTED);
        empty.setPadding(0, (int) (24 * dp), 0, 0);
        col.addView(empty);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        return scroll;
    }

    private TextView text(String s, float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Ui.font(this, weight));
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private TextView chip(String s) {
        TextView c = text(s, 14, 700, Ui.TEXT);
        c.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
        return c;
    }

    private void styleTabs() {
        TextView[] t = {tabVideos, tabShots};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 0) == showVideos;
            t[i].setTextColor(on ? Ui.INK_DARK : Ui.TEXT);
            t[i].setBackground(on ? Ui.round(Ui.AMBER, 30 * dp)
                    : Ui.round(0x00000000, 30 * dp, Math.max(1, (int) dp), 0x55A89D8E));
        }
    }

    private TextView action(String s, int color, View.OnClickListener l) {
        TextView t = text(s, 13, 700, color);
        t.setPadding((int) (14 * dp), (int) (7 * dp), (int) (14 * dp), (int) (7 * dp));
        t.setBackground(Ui.round(0x00000000, 30 * dp, Math.max(1, (int) dp), (color & 0x00FFFFFF) | 0x99000000));
        t.setOnClickListener(l);
        return t;
    }

    // ---------------------------------------------------------------- list

    private void reload() {
        styleTabs();
        final boolean videos = showVideos;
        final int gen = ++generation;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Item> items = query(getContentResolver(), videos);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen == generation && !isFinishing()) show(items);
                    }
                });
            }
        });
    }

    static ArrayList<Item> query(ContentResolver cr, boolean videos) {
        ArrayList<Item> out = new ArrayList<>();
        Uri coll;
        String folder = videos ? "Movies/" + Saver.FOLDER : "Pictures/" + Saver.FOLDER;
        if (Build.VERSION.SDK_INT >= 29) {
            coll = videos ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            coll = videos ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        String[] proj = videos
                ? new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.Video.VideoColumns.DURATION}
                : new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE};
        String sel;
        String[] args;
        if (Build.VERSION.SDK_INT >= 29) {
            sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
            args = new String[]{folder + "%"};
        } else {
            sel = MediaStore.MediaColumns.DATA + " LIKE ?";
            args = new String[]{"%/" + folder + "/%"};
        }
        Cursor c = null;
        try {
            c = cr.query(coll, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c == null) return out;
            while (c.moveToNext()) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(coll, c.getLong(0));
                it.name = c.getString(1);
                it.date = c.getLong(2) * 1000L;
                it.size = c.getLong(3);
                it.duration = videos ? c.getLong(4) : 0;
                it.video = videos;
                out.add(it);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private void show(ArrayList<Item> items) {
        list.removeAllViews();
        if (items.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(showVideos ? "No videos yet. Record one from the main screen."
                    : "No screenshots yet. Use the camera button on the panel while recording.");
        } else {
            empty.setVisibility(View.GONE);
        }
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US);
        for (final Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = (int) (12 * dp);
            row.setPadding(p, p, p, p);
            row.setBackground(Ui.round(Ui.CARD, 16 * dp, Math.max(1, (int) dp), Ui.CARD_LINE));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = (int) (10 * dp);

            final ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(Ui.round(0xFF2E2823, 10 * dp));
            thumb.setClipToOutline(true);
            row.addView(thumb, new LinearLayout.LayoutParams((int) (64 * dp), (int) (96 * dp)));
            loadThumb(it, thumb);
            thumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(it);
                }
            });

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding((int) (12 * dp), 0, 0, 0);
            TextView name = text(it.name == null ? "Untitled" : it.name, 14, 700, Ui.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(name);
            String meta = df.format(new Date(it.date));
            if (it.video && it.duration > 0) {
                long s = it.duration / 1000;
                meta += "  ·  " + String.format(Locale.US, "%d:%02d", s / 60, s % 60);
            }
            meta += "  ·  " + Formatter.formatShortFileSize(this, it.size);
            TextView m = text(meta, 12, 450, Ui.MUTED);
            m.setPadding(0, (int) (2 * dp), 0, (int) (8 * dp));
            info.addView(m);
            LinearLayout acts = new LinearLayout(this);
            acts.addView(action(it.video ? "Play" : "Open", Ui.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(it);
                }
            }));
            acts.addView(space());
            acts.addView(action("Share", Ui.AMBER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    share(it);
                }
            }));
            acts.addView(space());
            acts.addView(action("Delete", Ui.VERMILION, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(it);
                }
            }));
            info.addView(acts);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            list.addView(row, rl);
        }
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * dp), 1));
        return v;
    }

    private void loadThumb(final Item it, final ImageView into) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        b = getContentResolver().loadThumbnail(it.uri, new Size(240, 360), null);
                    } else {
                        long id = ContentUris.parseId(it.uri);
                        b = it.video
                                ? MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                                : MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    }
                } catch (Throwable ignored) {
                }
                final Bitmap bmp = b;
                if (bmp != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            into.setImageBitmap(bmp);
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------- actions

    private void open(Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, it.video ? "video/mp4" : "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void share(Item it) {
        Intent i = new Intent(Intent.ACTION_SEND).setType(it.video ? "video/mp4" : "image/png")
                .putExtra(Intent.EXTRA_STREAM, it.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Throwable t) {
            Toast.makeText(this, "No app found to share with", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(final Item it) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Delete this " + (it.video ? "video" : "screenshot") + "?")
                .setMessage(it.name + "\n\nIt will be removed from your phone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        delete(it);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete(Item it) {
        try {
            getContentResolver().delete(it.uri, null, null);
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            forgetIfLast(it);
            reload();
        } catch (SecurityException e) {
            // A file this app did not create (e.g. after reinstalling): Android asks the user.
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(it.uri));
                    startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                    return;
                }
                if (Build.VERSION.SDK_INT == 29 && e instanceof RecoverableSecurityException) {
                    startIntentSenderForResult(((RecoverableSecurityException) e).getUserAction().getActionIntent()
                            .getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                    return;
                }
            } catch (Throwable ignored) {
            }
            Toast.makeText(this, "Android did not allow deleting this file", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Could not delete: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void forgetIfLast(Item it) {
        String last = Prefs.lastUri(this);
        if (last != null && last.equals(it.uri.toString())) Prefs.setLast(this, null, null, 0);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_DELETE) {
            if (result == RESULT_OK) Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            reload();
        }
    }

    /** Lets older FrameCast files show up too (ones Android no longer counts as ours). */
    private void askReadPermission() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES};
        } else {
            perms = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(perms, REQ_READ);
                return;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        reload();
    }
}
