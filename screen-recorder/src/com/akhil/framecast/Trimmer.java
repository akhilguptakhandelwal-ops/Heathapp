package com.akhil.framecast;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;

/**
 * Cuts a part out of an MP4 without re-encoding (fast, no quality loss).
 * The start snaps back to the nearest key frame, at most about 1 second
 * earlier, because recordings have a key frame every second.
 */
final class Trimmer {
    private Trimmer() {
    }

    static void trim(String src, String dst, long startUs, long endUs) throws Exception {
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
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) {
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

            // Find where the video really starts (the key frame at or before startUs).
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long base = Long.MAX_VALUE;
            while (ex.getSampleTrackIndex() >= 0) {
                if (ex.getSampleTrackIndex() == videoTrack) {
                    base = ex.getSampleTime();
                    break;
                }
                ex.advance();
            }
            if (base == Long.MAX_VALUE) throw new IllegalStateException("Nothing to keep");
            ex.seekTo(base, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            ByteBuffer buf = ByteBuffer.allocateDirect(Math.max(maxSize, 4 << 20));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean[] done = new boolean[n];
            int open = 0;
            for (int i = 0; i < n; i++) if (map[i] >= 0) open++;
            mux.start();
            long written = 0;
            while (open > 0) {
                int ti = ex.getSampleTrackIndex();
                if (ti < 0) break;
                long t = ex.getSampleTime();
                if (map[ti] < 0 || done[ti]) {
                    ex.advance();
                    continue;
                }
                if (t > endUs) {
                    done[ti] = true;
                    open--;
                    ex.advance();
                    continue;
                }
                if (t < base) {          // audio from before the first kept frame
                    ex.advance();
                    continue;
                }
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long pts = t - base;
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, pts, flags);
                mux.writeSampleData(map[ti], buf, info);
                written++;
                ex.advance();
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
}
