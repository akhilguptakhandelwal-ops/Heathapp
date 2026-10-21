package com.akhil.framecast;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;

final class Shots {
    static void save(Bitmap b, String name) {
        try {
            File dir = new File(System.getProperty("shots", "target/shots"));
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static int dist(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
    }
}
