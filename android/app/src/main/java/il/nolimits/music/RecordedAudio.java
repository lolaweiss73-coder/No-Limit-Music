package il.nolimits.music;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Storage and lossless PCM edge trimming for opt-in device audio captures.
 * Keeps a playable WAV only if the operating system actually captured sound.
 */
final class RecordedAudio {
    static final int RATE = 44100;
    static final int CHANNELS = 2;
    static final int BYTES_PER_FRAME = 4; // signed 16-bit little-endian stereo
    private static final long MIN_FRAMES = RATE * 5L;
    private static final long MAX_EDGE_FRAMES = RATE * 25L;
    private static final long PAD_FRAMES = RATE / 5L;
    private static final int SILENCE_THRESHOLD = 320; // ~ -40 dBFS

    static File folder(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "recorded-songs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create recordings folder");
        return dir;
    }

    static File song(Context context, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}"))
            throw new IllegalArgumentException("Bad song id");
        return new File(folder(context), id + ".wav");
    }

    static boolean saved(Context context, String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}") && song(context, id).isFile()
                && song(context, id).length() > 44L + MIN_FRAMES * BYTES_PER_FRAME;
    }

    static Uri uri(Context context, String id) { return Uri.fromFile(song(context, id)); }

    static boolean delete(Context context, String id) {
        File file = song(context, id);
        return !file.exists() || file.delete();
    }

    /** Transforms temporary 44.1-kHz stereo PCM into an independently playable WAV. */
    static Result finish(Context context, String id, File pcm) throws IOException {
        long usable = pcm.length() - pcm.length() % BYTES_PER_FRAME;
        long frames = usable / BYTES_PER_FRAME;
        if (frames < MIN_FRAMES) throw new IOException("הקלטה קצרה מדי");

        long first = -1, last = -1;
        long frameIndex = 0;
        int maxAmplitude = 0;
        // Read raw PCM in chunks to avoid loading large recordings in RAM.
        try (FileInputStream input = new FileInputStream(pcm)) {
            byte[] bytes = new byte[32 * 1024];
            int count;
            while ((count = input.read(bytes)) != -1) {
                count -= count % BYTES_PER_FRAME;
                for (int i = 0; i < count; i += BYTES_PER_FRAME) {
                    int left = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
                    int right = (short) ((bytes[i + 2] & 0xff) | (bytes[i + 3] << 8));
                    int magnitude = Math.max(Math.abs(left), Math.abs(right));
                    maxAmplitude = Math.max(maxAmplitude, magnitude);
                    if (magnitude > SILENCE_THRESHOLD) {
                        if (first == -1) first = frameIndex;
                        last = frameIndex;
                    }
                    frameIndex++;
                }
            }
        }
        if (first < 0 || maxAmplitude <= SILENCE_THRESHOLD)
            throw new IOException("Android לא סיפקה שמע להקלטה");
        if (last - first < MIN_FRAMES)
            throw new IOException("אין מספיק שמע שנקלט כדי לשמור שיר");

        long start = first > RATE * 1L
                ? Math.max(0, Math.min(first - PAD_FRAMES, MAX_EDGE_FRAMES)) : 0;
        long end = frames - last > RATE
                ? Math.min(frames, Math.max(last + PAD_FRAMES, frames - MAX_EDGE_FRAMES)) : frames;
        if (end - start < MIN_FRAMES) { start = 0; end = frames; }

        long bytesToCopy = (end - start) * BYTES_PER_FRAME;
        if (bytesToCopy > Integer.MAX_VALUE - 44L)
            throw new IOException("ההקלטה ארוכה מדי לפורמט WAV");

        File output = song(context, id);
        File working = new File(folder(context), id + ".working");
        if (working.exists() && !working.delete()) throw new IOException("Cannot replace temporary output");

        try {
            try (FileOutputStream target = new FileOutputStream(working);
                 RandomAccessFile source = new RandomAccessFile(pcm, "r")) {
                target.write(wavHeader(bytesToCopy));
                source.seek(start * BYTES_PER_FRAME);
                byte[] buf = new byte[64 * 1024];
                long remain = bytesToCopy;
                while (remain > 0) {
                    int amount = source.read(buf, 0, (int) Math.min(buf.length, remain));
                    if (amount == -1) throw new IOException("Unexpected EOF");
                    target.write(buf, 0, amount);
                    remain -= amount;
                }
                target.getFD().sync();
            }
            if (output.exists() && !output.delete()) throw new IOException("Cannot replace old recording");
            if (!working.renameTo(output)) throw new IOException("Cannot publish recording");
        } finally {
            if (working.exists()) working.delete();
        }

        return new Result((end - start) * 1000 / RATE, start * 1000 / RATE,
                (frames - end) * 1000 / RATE);
    }

    static final class Result {
        final long durationMs, beginningRemovedMs, endingRemovedMs;
        Result(long durationMs, long beginningRemovedMs, long endingRemovedMs) {
            this.durationMs = durationMs;
            this.beginningRemovedMs = beginningRemovedMs;
            this.endingRemovedMs = endingRemovedMs;
        }
    }

    private static byte[] wavHeader(long pcmLength) {
        byte[] out = new byte[44];
        label(out, 0, "RIFF");
        little32(out, 4, pcmLength + 36);
        label(out, 8, "WAVE");
        label(out, 12, "fmt ");
        little32(out, 16, 16);
        little16(out, 20, 1);
        little16(out, 22, CHANNELS);
        little32(out, 24, RATE);
        little32(out, 28, RATE * BYTES_PER_FRAME);
        little16(out, 32, BYTES_PER_FRAME);
        little16(out, 34, 16);
        label(out, 36, "data");
        little32(out, 40, pcmLength);
        return out;
    }

    private static void label(byte[] dst, int at, String text) {
        for (int i = 0; i < 4; i++) dst[at + i] = (byte) text.charAt(i);
    }
    private static void little16(byte[] dst, int at, long v) {
        dst[at] = (byte) v;
        dst[at + 1] = (byte) (v >>> 8);
    }
    private static void little32(byte[] dst, int at, long v) {
        for (int i = 0; i < 4; i++) dst[at + i] = (byte) (v >>> (i * 8));
    }
}
