package com.akhil.framecast;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import java.nio.ByteBuffer;

/** Microphone -> AAC, written to the shared muxer. */
final class AudioEncoder implements Runnable {
    private static final int RATE = 44100;
    private static final int CHUNK = 2048;   // bytes per read (~23 ms)

    private AudioRecord rec;
    private MediaCodec codec;
    private MuxerSink mux;
    private Thread thread;
    private volatile boolean stop;
    private int track = -1;

    /** Loudest sample seen so far (0 = silence, 32767 = max). */
    private volatile int peak;
    /** Why the mic could not be used, or null. */
    volatile String problem;

    boolean prepare() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            problem = "This phone does not support mic recording at 44.1 kHz";
            return false;
        }
        // Some phones only give sound from one of these sources.
        int[] sources = {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION};
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 16384));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                    break;
                }
                r.release();
            } catch (Throwable ignored) {
            }
        }
        if (rec == null) {
            problem = "The microphone could not be opened";
            return false;
        }
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return true;
        } catch (Throwable t) {
            problem = "The audio encoder did not start";
            release();
            return false;
        }
    }

    void start(MuxerSink m) {
        mux = m;
        thread = new Thread(this, "framecast-audio");
        thread.start();
    }

    boolean heardSound() {
        return peak > 60;
    }

    void stopAndJoin() {
        stop = true;
        if (thread != null) {
            try {
                thread.join(4000);
            } catch (InterruptedException ignored) {
            }
        }
        release();
    }

    @Override
    public void run() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            codec.start();
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                problem = "Another app is using the microphone";
            }
            long startUs = System.nanoTime() / 1000;
            long samples = 0;
            byte[] pcm = new byte[CHUNK];
            boolean eosQueued = false;
            long eosAt = 0;
            while (true) {
                if (!eosQueued) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        long pts = startUs + samples * 1000000L / RATE;
                        if (stop) {
                            codec.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosQueued = true;
                            eosAt = System.currentTimeMillis();
                        } else {
                            ByteBuffer b = codec.getInputBuffer(in);
                            b.clear();
                            int want = Math.min(b.remaining(), CHUNK) & ~1;
                            // Read into a plain array: works on every phone, and lets us check the level.
                            int n = rec.read(pcm, 0, want);
                            if (n <= 0) {
                                java.util.Arrays.fill(pcm, 0, want, (byte) 0);
                                n = want;
                            } else {
                                trackPeak(pcm, n);
                            }
                            b.put(pcm, 0, n);
                            codec.queueInputBuffer(in, 0, n, pts, 0);
                            samples += n / 2;
                        }
                    }
                }
                if (drain(info)) break;
                if (eosQueued && System.currentTimeMillis() - eosAt > 3000) break;
            }
        } catch (Throwable t) {
            if (problem == null) problem = "The microphone stopped: " + t.getMessage();
        } finally {
            if (track < 0 && mux != null) mux.dropTrack();
        }
    }

    private void trackPeak(byte[] pcm, int n) {
        int p = peak;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (s < 0) s = -s;
            if (s > p) p = s;
        }
        peak = p;
    }

    /** Returns true at end of stream. */
    private boolean drain(MediaCodec.BufferInfo info) {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(codec.getOutputFormat());
            } else if (idx >= 0) {
                ByteBuffer out = codec.getOutputBuffer(idx);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && out != null && info.size > 0) {
                    mux.write(track, out, info);
                }
                codec.releaseOutputBuffer(idx, false);
                if (eos) return true;
            }
        }
    }

    private void release() {
        try {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
        } catch (Exception ignored) {
        }
        rec = null;
        try {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
        } catch (Exception ignored) {
        }
        codec = null;
    }
}
