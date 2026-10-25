package com.akhil.framecast;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that mirrors the screen into an ImageReader, draws every
 * frame inside a phone frame onto the encoder surface, and writes an MP4.
 */
public class RecorderService extends Service {
    static final String ACTION_START = "com.akhil.framecast.START";
    static final String ACTION_STOP = "com.akhil.framecast.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "recording";
    private static final int NOTIF_REC = 7, NOTIF_SAVED = 8;
    private static final int FPS = 30;

    /** True from permission grant until the file is saved. */
    public static volatile boolean running;
    static volatile RecorderService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread renderThread;
    private Handler render;

    private Prefs prefs;
    private volatile OverlayController overlay;
    private volatile int screenW, screenH;

    /** Arrows, boxes and cursor, painted straight into the video. */
    private final FrameRenderer.Marks marks = new FrameRenderer.Marks() {
        @Override
        public void draw(Canvas c) {
            OverlayController o = overlay;
            if (o != null) o.drawMarks(c);
        }
    };
    private MediaProjection projection;
    private boolean micWanted;
    private volatile boolean stopping;

    // Render-thread state.
    private FrameRenderer fr;
    private ImageReader reader;
    private VirtualDisplay vd;
    private MediaCodec venc;
    private Surface encSurface;
    private Thread vDrain;
    private volatile boolean videoEosSent;
    private volatile AudioEncoder audio;
    private MuxerSink mux;
    private File tmp;
    private Bitmap shot;
    private int shotW, shotH;
    private ByteBuffer scratch;
    private boolean useHw = true;
    private boolean capturing;
    private boolean lastPortrait;
    private int dpi;
    private long frameNo, firstTick;
    private volatile long startedAt;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        renderThread = new HandlerThread("framecast-render");
        renderThread.start();
        render = new Handler(renderThread.getLooper());
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (renderThread != null) renderThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || running) return START_NOT_STICKY;

        prefs = new Prefs(this);
        micWanted = prefs.mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (prefs.mic && !micWanted) {
            Toast.makeText(this, "Microphone permission is off, so no voice will be recorded. Allow it on the FrameCast screen.", Toast.LENGTH_LONG).show();
        }
        try {
            goForeground(micWanted);
        } catch (Throwable t) {
            if (micWanted) {
                Toast.makeText(this, "Android did not allow the microphone, so no voice will be recorded.", Toast.LENGTH_LONG).show();
            }
            micWanted = false;
            try {
                goForeground(false);
            } catch (Throwable t2) {
                fail("Could not start recording: " + t2.getMessage());
                return START_NOT_STICKY;
            }
        }
        try {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            projection = null;
        }
        if (projection == null) {
            fail("Screen recording permission was not given");
            return START_NOT_STICKY;
        }
        projection.registerCallback(projectionCallback, main);
        running = true;
        stopping = false;
        overlay = makeOverlay();
        if (overlay != null) overlay.show();
        countdown(3);
        return START_NOT_STICKY;
    }

    /** Tool panel ("display over other apps"); null if that permission is off. */
    private OverlayController makeOverlay() {
        if (!Settings.canDrawOverlays(this)) return null;
        return new OverlayController(this);
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(RecorderService.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** A few seconds in: warn once if the mic hears nothing at all. */
    private final Runnable micCheck = new Runnable() {
        @Override
        public void run() {
            AudioEncoder a = audio;
            if (!running || stopping || a == null) return;
            if (a.problem != null) toast("Voice is not being recorded: " + a.problem);
            else if (!a.heardSound()) toast("The microphone hears no sound. Check that no other app (call, voice recorder) is using it, and that Microphone access is on in phone Settings.");
        }
    };

    // ----------------------------------------------------------- screenshot

    /** Saves the current frame (phone frame included) as a PNG. Any thread. */
    void screenshot() {
        render.post(new Runnable() {
            @Override
            public void run() {
                String msg;
                if (!capturing || fr == null || shot == null || shotW == 0) {
                    msg = "Take the screenshot after recording has started";
                } else {
                    Bitmap out = null;
                    try {
                        out = Bitmap.createBitmap(fr.outW, fr.outH, Bitmap.Config.ARGB_8888);
                        fr.draw(new Canvas(out), shot, shotW, shotH, marks, screenW, screenH);
                        String name = "FrameCast_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
                        Saver.savePng(RecorderService.this, out, name);
                        msg = "Screenshot saved: Pictures/FrameCast";
                    } catch (Throwable t) {
                        msg = "Could not save screenshot: " + t.getMessage();
                    } finally {
                        if (out != null) out.recycle();
                    }
                }
                final String m = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(RecorderService.this, m, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            requestStop();
        }
    };

    // ---------------------------------------------------------------- start

    private void countdown(final int n) {
        if (!running || stopping) return;
        OverlayController o = overlay;
        if (n <= 0) {
            if (o != null) o.setTimer("REC", true);
            render.post(new Runnable() {
                @Override
                public void run() {
                    begin();
                }
            });
            return;
        }
        if (o != null) o.setTimer(String.valueOf(n), false);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                countdown(n - 1);
            }
        }, 1000);
    }

    /** Runs on the render thread. */
    private void begin() {
        if (stopping || projection == null) return;
        try {
            Point real = realSize();
            screenW = real.x;
            screenH = real.y;
            float aspect = real.x / (float) real.y;
            lastPortrait = real.y >= real.x;
            boolean ok = false;
            for (int[] size : prefs.outputCandidates(aspect)) {
                if (setupEncoder(size, aspect)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) throw new IllegalStateException("The video encoder did not work on this phone");
            DisplayMetrics dm = getResources().getDisplayMetrics();
            dpi = dm.densityDpi;
            reader = newReader(fr.capW, fr.capH);
            vd = projection.createVirtualDisplay("FrameCast", fr.capW, fr.capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, render);

            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getCacheDir();
            tmp = new File(dir, "recording.mp4");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            audio = null;
            if (micWanted) {
                AudioEncoder a = new AudioEncoder();
                if (a.prepare()) audio = a;
                else toast("Voice is not being recorded: " + a.problem);
            }
            mux = new MuxerSink(tmp.getPath(), audio != null ? 2 : 1);
            videoEosSent = false;
            vDrain = new Thread(videoDrain, "framecast-video");
            vDrain.start();
            if (audio != null) {
                audio.start(mux);
                main.postDelayed(micCheck, 4000);
            }

            capturing = true;
            startedAt = SystemClock.elapsedRealtime();
            frameNo = 0;
            firstTick = SystemClock.uptimeMillis();
            framesIn = 0;
            sawContent = false;
            render.post(tick);
            main.post(timerUpdater);
            main.postDelayed(screenCheck, 5000);
        } catch (Throwable t) {
            final String msg = t.getMessage();
            main.post(new Runnable() {
                @Override
                public void run() {
                    fail("Could not start recording: " + msg);
                }
            });
        }
    }

    private boolean setupEncoder(int[] size, float aspect) {
        MediaCodec c = null;
        FrameRenderer f = null;
        try {
            f = new FrameRenderer(size[0], size[1], prefs.phone, prefs.background, prefs.plain());
            f.layout(aspect);
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size[0], size[1]);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, (int) (size[0] * (long) size[1] * 4L));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface s = c.createInputSurface();
            c.start();
            venc = c;
            encSurface = s;
            fr = f;
            return true;
        } catch (Throwable t) {
            if (c != null) {
                try {
                    c.release();
                } catch (Throwable ignored) {
                }
            }
            if (f != null) f.release();
            return false;
        }
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(imageListener, render);
        return r;
    }

    private Point realSize() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        d.getRealSize(p);
        if (p.x <= 0 || p.y <= 0) p.set(1080, 2400);
        return p;
    }

    // -------------------------------------------------------------- frames

    private final ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader r) {
            if (r != reader) return;
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int ps = Math.max(1, plane.getPixelStride());
                int rs = plane.getRowStride();
                int w = img.getWidth(), h = img.getHeight();
                int bw = rs / ps;
                if (shot == null || shot.getWidth() != bw || shot.getHeight() != h) {
                    shot = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
                }
                int need = bw * h * 4;
                buf.rewind();
                if (buf.remaining() >= need) {
                    shot.copyPixelsFromBuffer(buf);
                } else {
                    if (scratch == null || scratch.capacity() < need) scratch = ByteBuffer.allocateDirect(need);
                    scratch.clear();
                    scratch.put(buf);
                    scratch.position(0);
                    scratch.limit(need);
                    shot.copyPixelsFromBuffer(scratch);
                }
                framesIn++;
                if (!sawContent && framesIn % 10 == 1) sawContent = hasContent(shot, w, h);
                hidePanel(w, h);
                shotW = w;
                shotH = h;
            } catch (Throwable ignored) {
            } finally {
                if (img != null) img.close();
            }
        }
    };

    private volatile int framesIn;
    private volatile boolean sawContent;

    /** False when every sampled pixel is pure black (what Android sends when capture is blocked). */
    private static boolean hasContent(Bitmap b, int w, int h) {
        for (int gy = 1; gy < 12; gy++) {
            for (int gx = 1; gx < 8; gx++) {
                int p = b.getPixel(w * gx / 8, h * gy / 12);
                if ((p & 0x00FFFFFF) != 0) return true;
            }
        }
        return false;
    }

    /** A few seconds in: say so if the screen picture is missing or black. */
    private final Runnable screenCheck = new Runnable() {
        @Override
        public void run() {
            if (!running || stopping) return;
            if (framesIn == 0) {
                toast("Android is not sending the screen picture. Stop, start again and allow \"Entire screen\".");
            } else if (!sawContent) {
                toast("The screen is coming through black. Stop, start again and choose \"Entire screen\" (not a single app). Some apps (banking, Netflix) also block recording.");
            }
        }
    };

    /** Paints over the tool panel so it never shows in the video. */
    private void hidePanel(int w, int h) {
        OverlayController o = overlay;
        if (o == null || screenW <= 0 || screenH <= 0) return;
        float sx = w / (float) screenW, sy = h / (float) screenH;
        float pad = 3 * getResources().getDisplayMetrics().density * sx;
        try {
            PanelMask.apply(shot, w, h, o.panelRect(), sx, sy, pad);
        } catch (Throwable ignored) {
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!capturing) return;
            frameNo++;
            if (frameNo % 15 == 0) checkRotation();
            drawFrame();
            long next = firstTick + frameNo * 1000L / FPS;
            long now = SystemClock.uptimeMillis();
            if (next < now) {
                // Running late: skip ahead instead of bursting frames.
                frameNo = (now - firstTick) * FPS / 1000;
                next = firstTick + (frameNo + 1) * 1000L / FPS;
            }
            render.postAtTime(this, next);
        }
    };

    private void drawFrame() {
        Canvas c;
        try {
            c = useHw ? encSurface.lockHardwareCanvas() : encSurface.lockCanvas(null);
        } catch (Throwable t) {
            if (!useHw) return;
            useHw = false;
            try {
                c = encSurface.lockCanvas(null);
            } catch (Throwable t2) {
                return;
            }
        }
        try {
            fr.draw(c, shot, shotW, shotH, marks, screenW, screenH);
        } catch (Throwable ignored) {
        } finally {
            try {
                encSurface.unlockCanvasAndPost(c);
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkRotation() {
        try {
            Point p = realSize();
            boolean portrait = p.y >= p.x;
            if (portrait == lastPortrait) return;
            lastPortrait = portrait;
            screenW = p.x;
            screenH = p.y;
            fr.layout(p.x / (float) p.y);
            ImageReader nr = newReader(fr.capW, fr.capH);
            vd.resize(fr.capW, fr.capH, dpi);
            vd.setSurface(nr.getSurface());
            ImageReader old = reader;
            reader = nr;
            old.close();
            shot = null;
            shotW = shotH = 0;
        } catch (Throwable ignored) {
        }
    }

    private final Runnable videoDrain = new Runnable() {
        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            long eosWait = 0;
            try {
                while (true) {
                    int idx = venc.dequeueOutputBuffer(info, 10000);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(venc.getOutputFormat());
                    } else if (idx >= 0) {
                        ByteBuffer out = venc.getOutputBuffer(idx);
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && out != null && info.size > 0) {
                            mux.write(track, out, info);
                        }
                        venc.releaseOutputBuffer(idx, false);
                        if (eos) break;
                    } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER && videoEosSent) {
                        long now = SystemClock.uptimeMillis();
                        if (eosWait == 0) eosWait = now;
                        else if (now - eosWait > 3000) break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private final Runnable timerUpdater = new Runnable() {
        @Override
        public void run() {
            if (!running || stopping || startedAt == 0) return;
            long s = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            OverlayController o = overlay;
            if (o != null) o.setTimer(String.format(Locale.US, "%02d:%02d", s / 60, s % 60), true);
            main.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- stop

    /** Safe to call from any thread, any number of times. */
    void requestStop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!running || stopping) {
                    if (!running) stopSelf();
                    return;
                }
                stopping = true;
                OverlayController o = overlay;
                if (o != null) o.setTimer("Saving", false);
                render.post(new Runnable() {
                    @Override
                    public void run() {
                        finishOnRender();
                    }
                });
            }
        });
    }

    private void finishOnRender() {
        capturing = false;
        render.removeCallbacks(tick);
        try {
            if (vd != null) vd.release();
        } catch (Throwable ignored) {
        }
        vd = null;
        try {
            if (projection != null) {
                projection.unregisterCallback(projectionCallback);
                projection.stop();
            }
        } catch (Throwable ignored) {
        }
        projection = null;
        if (venc != null) {
            try {
                venc.signalEndOfInputStream();
            } catch (Throwable ignored) {
            }
            videoEosSent = true;
        }
        boolean silent = false;
        if (audio != null) {
            audio.stopAndJoin();
            silent = !audio.heardSound();
        }
        audio = null;
        if (vDrain != null) {
            try {
                vDrain.join(5000);
            } catch (InterruptedException ignored) {
            }
        }
        vDrain = null;
        try {
            if (venc != null) {
                venc.stop();
                venc.release();
            }
        } catch (Throwable ignored) {
        }
        venc = null;
        try {
            if (encSurface != null) encSurface.release();
        } catch (Throwable ignored) {
        }
        encSurface = null;
        boolean ok = mux != null && mux.finish();
        mux = null;
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        reader = null;
        if (fr != null) fr.release();
        fr = null;
        shot = null;

        long dur = startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt;
        startedAt = 0;
        String message;
        boolean ready = false;
        if (ok && tmp != null && tmp.length() > 0) {
            // An older recording still waiting for a decision is saved, never lost.
            String old = Prefs.pendingPath(this);
            if (old != null && new File(old).exists()) {
                try {
                    String oldName = "FrameCast_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date(new File(old).lastModified())) + ".mp4";
                    Uri u = Saver.save(this, new File(old), oldName);
                    Prefs.setLast(this, u.toString(), oldName, Prefs.pendingMs(this));
                    //noinspection ResultOfMethodCallIgnored
                    new File(old).delete();
                } catch (Throwable ignored) {
                }
            }
            File keep = new File(tmp.getParentFile(), "rec_" + System.currentTimeMillis() + ".mp4");
            if (!tmp.renameTo(keep)) keep = tmp;
            Prefs.setPending(this, keep.getAbsolutePath(), dur);
            ready = true;
            message = "Recording ready: Save, Trim or Discard";
            if (!sawContent) message += ". The screen came through black: choose \"Entire screen\" next time.";
            if (silent) message += ". No voice: the mic heard nothing.";
        } else {
            message = dur < 1500 ? "Recording was too short, not saved" : "Could not save the recording";
        }
        final String msg = message;
        final boolean saved = ready;
        main.post(new Runnable() {
            @Override
            public void run() {
                finishOnMain(msg, saved);
            }
        });
    }

    private void finishOnMain(String msg, boolean saved) {
        running = false;
        stopping = false;
        main.removeCallbacks(timerUpdater);
        OverlayController o = overlay;
        if (o != null) o.hide();
        overlay = null;
        stopForegroundCompat();
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        if (saved) notifySaved(msg);
        openApp();
        stopSelf();
    }

    private void fail(String msg) {
        running = false;
        stopping = false;
        try {
            if (projection != null) {
                projection.unregisterCallback(projectionCallback);
                projection.stop();
            }
        } catch (Throwable ignored) {
        }
        projection = null;
        OverlayController o = overlay;
        if (o != null) o.hide();
        overlay = null;
        stopForegroundCompat();
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    private void openApp() {
        // MainActivity forwards to the review screen while a recording is waiting.
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("saved", true);
        try {
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------- notifications

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent appIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void goForeground(boolean mic) {
        ensureChannel();
        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("FrameCast is recording")
                .setContentText("Tap Stop to save the video to your Gallery")
                .setContentIntent(appIntent())
                .setOngoing(true)
                .setColor(Ui.VERMILION)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_REC, n, type);
        } else {
            startForeground(NOTIF_REC, n);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) {
        }
    }

    private void notifySaved(String msg) {
        try {
            ensureChannel();
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("Recording ready")
                    .setContentText(msg)
                    .setContentIntent(appIntent())
                    .setAutoCancel(true)
                    .setColor(Ui.AMBER)
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_SAVED, n);
        } catch (Throwable ignored) {
        }
    }
}
