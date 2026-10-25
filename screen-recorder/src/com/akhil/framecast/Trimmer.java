package com.akhil.framecast;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;

/**
 * Keeps chosen parts of an MP4 and joins them, without re-encoding (fast, no
 * quality loss). Each kept part starts at the key frame at or just before its
 * start; recordings have a key frame every second, so a cut can be off by up
 * to about 1 second.
 */
final class Trimmer {
    private static final long FRAME_US = 33_333;

    private Trimmer() {
    }

    static void trim(String src, String dst, long startUs, long endUs, boolean keepAudio) throws Exception {
        keep(src, dst, new long[][]{{startUs, endUs}}, keepAudio);
    }

    /**
     * Writes only the parts {@code keep[i] = {startUs, endUs}} (in order, not
     * overlapping) one after another. {@code keepAudio = false} leaves the voice out.
     */
    static void keep(String src, String dst, long[][] keep, boolean keepAudio) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(src);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            int videoTrack = -1;
            int maxSize = 1 << 20;
            mux = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !(mime.startsWith("video/") || (keepAudio && mime.startsWith("audio/")))) {
                    map[i] = -1;
                    continue;
                }
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i;
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSize = Math.max(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
                map[i] = mux.addTrack(f);
                ex.selectTrack(i);
            }
            if (videoTrack < 0) throw new IllegalStateException("No video in this file");

            ByteBuffer buf = ByteBuffer.allocateDirect(Math.max(maxSize, 4 << 20));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            mux.start();
            long offset = 0;          // where the next part starts in the new video
            long written = 0;
            long lastEndOut = -1;
            for (long[] part : keep) {
                long startUs = part[0], endUs = part[1];
                if (endUs <= startUs) continue;

                // The part really starts at the key frame at or before startUs.
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long base = -1;
                while (ex.getSampleTrackIndex() >= 0) {
                    if (ex.getSampleTrackIndex() == videoTrack) {
                        base = ex.getSampleTime();
                        break;
                    }
                    ex.advance();
                }
                if (base < 0 || base > endUs) continue;
                // Go back to that key frame and copy from there.
                ex.seekTo(base, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                long maxOut = offset;
                while (true) {
                    int ti = ex.getSampleTrackIndex();
                    if (ti < 0) break;
                    long t = ex.getSampleTime();
                    if (t > endUs) {
                        // Past this part. Video decides; stray audio before the
                        // last video frame is simply skipped.
                        if (ti == videoTrack) break;
                        ex.advance();
                        continue;
                    }
                    if (map[ti] < 0 || t < base) {
                        ex.advance();
                        continue;
                    }
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long pts = offset + (t - base);
                    if (ti != videoTrack && pts <= lastEndOut) {   // audio overlap at a join
                        ex.advance();
                        continue;
                    }
                    int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, size, pts, flags);
                    mux.writeSampleData(map[ti], buf, info);
                    written++;
                    maxOut = Math.max(maxOut, pts);
                    ex.advance();
                }
                lastEndOut = maxOut;
                offset = maxOut + FRAME_US;
            }
            if (written == 0) throw new IllegalStateException("Nothing to keep");
            mux.stop();
        } finally {
            try {
                if (mux != null) mux.release();
            } catch (Exception ignored) {
            }
            ex.release();
        }
    }

    /**
     * The parts to keep, as fractions 0..1 of the video, after removing
     * {@code cuts} (each {a, b}, may overlap, any order).
     */
    static float[][] keptParts(java.util.List<float[]> cuts) {
        java.util.ArrayList<float[]> sorted = new java.util.ArrayList<>();
        for (float[] c : cuts) sorted.add(new float[]{Math.min(c[0], c[1]), Math.max(c[0], c[1])});
        java.util.Collections.sort(sorted, new java.util.Comparator<float[]>() {
            @Override
            public int compare(float[] x, float[] y) {
                return Float.compare(x[0], y[0]);
            }
        });
        java.util.ArrayList<float[]> keep = new java.util.ArrayList<>();
        float pos = 0f;
        for (float[] c : sorted) {
            if (c[0] > pos) keep.add(new float[]{pos, c[0]});
            pos = Math.max(pos, c[1]);
        }
        if (pos < 1f) keep.add(new float[]{pos, 1f});
        return keep.toArray(new float[0][]);
    }
}
