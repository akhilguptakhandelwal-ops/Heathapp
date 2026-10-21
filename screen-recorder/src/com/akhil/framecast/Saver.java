package com.akhil.framecast;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies a finished recording into the phone's gallery (Movies/FrameCast). */
final class Saver {
    static final String FOLDER = "FrameCast";

    static Uri save(Context ctx, File src, String name) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IllegalStateException("Gallery refused the file");
            OutputStream out = cr.openOutputStream(uri);
            try {
                copy(new FileInputStream(src), out);
            } catch (Exception e) {
                cr.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name);
        copy(new FileInputStream(src), new FileOutputStream(dst));
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DATA, dst.getAbsolutePath());
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.TITLE, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("Gallery refused the file");
        return uri;
    }

    static Uri savePng(Context ctx, android.graphics.Bitmap bmp, String name) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IllegalStateException("Gallery refused the file");
            OutputStream out = cr.openOutputStream(uri);
            try {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                if (out != null) out.close();
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name);
        OutputStream out = new FileOutputStream(dst);
        try {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DATA, dst.getAbsolutePath());
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        return cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            if (out != null) out.close();
        }
    }
}
