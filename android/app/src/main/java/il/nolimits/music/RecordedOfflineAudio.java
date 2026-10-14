package il.nolimits.music;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Extracts the already-encoded AAC track from an MP4 into a compact M4A file. */
final class RecordedOfflineAudio {
    private static final int MAX_SAMPLE_BYTES = 4 * 1024 * 1024;

    static File folder(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "recorded-offline-audio");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IllegalStateException("Could not create audio folder");
        return dir;
    }

    static File song(Context context, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}"))
            throw new IllegalArgumentException("Invalid video ID");
        return new File(folder(context), id + ".m4a");
    }

    static boolean saved(Context context, String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}")
                && song(context,id).isFile() && song(context,id).length() > 1024;
    }

    static Uri uri(Context context,String id) {
        return Uri.fromFile(song(context,id));
    }

    static boolean delete(Context context,String id) {
        File f = song(context,id);
        return !f.exists() || f.delete();
    }

    /**
     * Copies the AAC samples without decoding/re-encoding, retaining quality
     * and minimizing CPU/battery. Original MP4 remains until extraction succeeds.
     */
    static void extract(Context context,String id,File videoFile) throws IOException {
        File target = song(context,id);
        File temp = new File(folder(context), id + ".working.m4a");
        if (temp.exists() && !temp.delete())
            throw new IOException("Cannot replace temporary audio");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        boolean complete = false;
        try {
            extractor.setDataSource(videoFile.getAbsolutePath());
            int audioTrack = -1;
            for (int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack=i;break;
                }
            }
            if (audioTrack < 0) throw new IOException("No audio track found in MP4");
            extractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(temp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack=muxer.addTrack(extractor.getTrackFormat(audioTrack));
            muxer.start();started=true;

            ByteBuffer sample = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long startTime = -1;
            long previous = -1;
            int written = 0;
            while (true) {
                sample.clear();
                int size=extractor.readSampleData(sample,0);
                if (size < 0) break;
                long pts=extractor.getSampleTime();
                if (pts < 0) break;
                if (startTime < 0) startTime=pts;
                info.set(0,size,Math.max(previous+1,pts-startTime),
                        extractor.getSampleFlags());
                sample.position(0);
                sample.limit(size);
                muxer.writeSampleData(outTrack,sample,info);
                previous=info.presentationTimeUs;
                written++;
                if (!extractor.advance()) break;
            }
            if (written<2) throw new IOException("Captured audio is empty");
            complete=true;
        } finally {
            if (muxer!=null) {
                if (started) try {muxer.stop();} catch (Exception e) {complete=false;}
                muxer.release();
            }
            extractor.release();
            if (!complete) temp.delete();
        }

        if (!temp.isFile() || temp.length()<1024)
            throw new IOException("Audio extraction produced no playable data");
        if (target.exists() && !target.delete())
            throw new IOException("Cannot replace saved audio");
        if (!temp.renameTo(target))
            throw new IOException("Cannot publish offline audio");
    }
}
