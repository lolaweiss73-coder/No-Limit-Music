package il.nolimits.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Analyze locally stored, authorized audio once. Only leading/trailing silence
 * is skipped; the original MP3 is never destroyed or re-encoded.
 */
final class AudioTrim {
    private static final String TAG = "NoLimitAudioTrim";
    private static final String PREFS = "audio_trim_v1";
    private static final ExecutorService WORKERS = Executors.newSingleThreadExecutor();
    private static final Set<String> IN_PROGRESS = new HashSet<>();
    private static final long MAX_EDGE_MS = 12000L;
    private static final long MIN_SILENCE_MS = 850L;
    private static final long SAFETY_MARGIN_MS = 200L;

    static final class Bounds {
        final long startMs;
        final long endMs; // -1: play to end of the file
        Bounds(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
        boolean changed() { return startMs > 0 || endMs >= 0; }
    }

    private static String fingerprint(Context c, Catalog.Track track) {
        File file = Catalog.local(c, track);
        return track.id + ":" + file.length() + ":" + file.lastModified();
    }

    private static SharedPreferences preferences(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Bounds get(Context c, Catalog.Track track) {
        if (!Catalog.saved(c, track)) return null;
        String prefix = fingerprint(c, track);
        SharedPreferences prefs = preferences(c);
        if (!prefs.getBoolean(prefix + ":done", false)) return null;
        return new Bounds(
                prefs.getLong(prefix + ":start", 0),
                prefs.getLong(prefix + ":end", -1)
        );
    }

    static void inspectAsync(Context c, Catalog.Track track) {
        if (!Catalog.saved(c, track)) return;
        final Context app = c.getApplicationContext();
        final String key = fingerprint(app, track);
        if (preferences(app).getBoolean(key + ":done", false)) return;
        synchronized (IN_PROGRESS) {
            if (!IN_PROGRESS.add(key)) return;
        }
        WORKERS.execute(() -> {
            try {
                Bounds bounds = analyze(Catalog.local(app, track));
                if (bounds != null) {
                    preferences(app).edit()
                            .putLong(key + ":start", bounds.startMs)
                            .putLong(key + ":end", bounds.endMs)
                            .putBoolean(key + ":done", true).apply();
                }
            } catch (Exception ex) {
                Log.w(TAG, "Could not analyze authorized local track " + track.id, ex);
            } finally {
                synchronized (IN_PROGRESS) { IN_PROGRESS.remove(key); }
            }
        });
    }

    private static Bounds analyze(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = f;
                    break;
                }
            }
            if (audioTrack == -1 || inputFormat == null
                    || !inputFormat.containsKey(MediaFormat.KEY_DURATION)) return null;
            long durationMs = inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000;
            if (durationMs < 15000 || durationMs > 3600000) return new Bounds(0, -1);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(audioTrack);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            boolean inputDone = false;
            boolean outputDone = false;
            long firstAudible = Long.MAX_VALUE;
            long lastAudible = -1;
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int idle = 0;

            while (!outputDone && idle < 250) {
                boolean didWork = false;
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inIndex);
                        if (input == null) throw new IllegalStateException("No decoder input buffer");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                        didWork = true;
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    if (output.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (output.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        pcmEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    didWork = true;
                } else if (outIndex >= 0) {
                    ByteBuffer pcm = codec.getOutputBuffer(outIndex);
                    if (pcm != null && info.size > 0 && sampleRate > 0 && channels > 0) {
                        int sampleBytes = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4
                                : pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ? 2 : 0;
                        if (sampleBytes == 0) return null;
                        pcm.order(ByteOrder.LITTLE_ENDIAN);
                        int frameBytes = sampleBytes * channels;
                        int frameCount = info.size / frameBytes;
                        // Sampling every ~2 milliseconds is enough for detecting silence.
                        int stride = Math.max(1, sampleRate / 500);
                        for (int frame = 0; frame < frameCount; frame += stride) {
                            int base = info.offset + frame * frameBytes;
                            float peak = 0;
                            for (int channel = 0; channel < channels; channel++) {
                                int at = base + channel * sampleBytes;
                                if (at + sampleBytes > info.offset + info.size) break;
                                float amplitude = sampleBytes == 2
                                        ? Math.abs((float) pcm.getShort(at) / 32768f)
                                        : Math.abs(pcm.getFloat(at));
                                peak = Math.max(peak, amplitude);
                            }
                            if (peak >= 0.004f) {
                                long ms = Math.max(0, info.presentationTimeUs / 1000
                                        + (frame * 1000L / sampleRate));
                                firstAudible = Math.min(firstAudible, ms);
                                lastAudible = Math.max(lastAudible, ms);
                            }
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    didWork = true;
                }
                idle = didWork ? 0 : idle + 1;
            }
            if (!outputDone || lastAudible < 0) return null;

            long start = firstAudible >= MIN_SILENCE_MS
                    ? Math.min(MAX_EDGE_MS, Math.max(0, firstAudible - SAFETY_MARGIN_MS))
                    : 0;
            long endGap = durationMs - lastAudible;
            long end = endGap >= MIN_SILENCE_MS
                    ? Math.max(durationMs - MAX_EDGE_MS,
                            Math.min(durationMs, lastAudible + SAFETY_MARGIN_MS))
                    : -1;
            if (end >= 0 && end - start < 12000) return new Bounds(0, -1);
            return new Bounds(start, end);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }
}
