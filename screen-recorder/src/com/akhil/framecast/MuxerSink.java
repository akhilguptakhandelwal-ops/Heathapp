package com.akhil.framecast;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Thread-safe wrapper around MediaMuxer. Starts once every expected track has
 * been added; samples that arrive earlier are held in memory.
 */
final class MuxerSink {
    private final MediaMuxer muxer;
    private int expected, added;
    private boolean started, finished;
    private long written;
    private final ArrayList<Pending> pending = new ArrayList<>();

    private static final class Pending {
        int track;
        byte[] data;
        MediaCodec.BufferInfo info;
    }

    MuxerSink(String path, int expectedTracks) throws IOException {
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        expected = expectedTracks;
    }

    synchronized int addTrack(MediaFormat format) {
        if (finished) return -1;
        int t = muxer.addTrack(format);
        added++;
        maybeStart();
        return t;
    }

    /** A track that will never deliver a format (e.g. the mic failed). */
    synchronized void dropTrack() {
        if (expected > 1) expected--;
        maybeStart();
    }

    private void maybeStart() {
        if (started || finished || added < expected || added == 0) return;
        muxer.start();
        started = true;
        for (Pending p : pending) {
            muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info);
            written++;
        }
        pending.clear();
    }

    synchronized void write(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (finished || track < 0 || info.size <= 0) return;
        buf.position(info.offset);
        buf.limit(info.offset + info.size);
        if (!started) {
            if (pending.size() > 600) return;
            Pending p = new Pending();
            p.track = track;
            p.data = new byte[info.size];
            buf.get(p.data);
            p.info = new MediaCodec.BufferInfo();
            p.info.set(0, info.size, info.presentationTimeUs, info.flags);
            pending.add(p);
            return;
        }
        muxer.writeSampleData(track, buf, info);
        written++;
    }

    /** Returns true when a playable file was written. */
    synchronized boolean finish() {
        if (finished) return false;
        finished = true;
        boolean ok = false;
        if (started) {
            try {
                muxer.stop();
                ok = written > 0;
            } catch (Exception ignored) {
            }
        }
        try {
            muxer.release();
        } catch (Exception ignored) {
        }
        return ok;
    }
}
