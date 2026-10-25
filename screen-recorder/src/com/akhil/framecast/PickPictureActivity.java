package com.akhil.framecast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.io.InputStream;

/**
 * Invisible screen behind the panel's Photo button: lets you pick a picture,
 * then hands it to the recorder to show full screen for drawing.
 */
public class PickPictureActivity extends Activity {
    private static final int REQ = 31;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return;   // already waiting for the picker
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(pick, "Choose a picture"), REQ);
        } catch (Throwable t) {
            Toast.makeText(this, "No app found to choose a picture", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        Uri uri = data != null ? data.getData() : null;
        if (req == REQ && result == RESULT_OK && uri != null) {
            Bitmap bmp = load(uri);
            RecorderService r = RecorderService.instance;
            if (bmp == null) {
                Toast.makeText(this, "Could not open this picture", Toast.LENGTH_SHORT).show();
            } else if (r == null || !RecorderService.running) {
                bmp.recycle();
                Toast.makeText(this, "Start a recording first", Toast.LENGTH_SHORT).show();
            } else {
                r.showPicture(bmp);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Loads the picture no bigger than the screen (right way up). */
    private Bitmap load(Uri uri) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        final int maxSide = Math.max(m.widthPixels, m.heightPixels);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        new ImageDecoder.OnHeaderDecodedListener() {
                            @Override
                            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                                float k = Math.min(1f, maxSide / (float) Math.max(w, h));
                                if (k < 1f) d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                            }
                        });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
