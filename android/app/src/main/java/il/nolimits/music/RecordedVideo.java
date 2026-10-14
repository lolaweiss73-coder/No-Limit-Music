package il.nolimits.music;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Creates an H.264/AAC MP4 from a user-approved temporary screen recording
 * and audio playback captured separately by Android. Never bypasses protected
 * surfaces or audio-capture restrictions.
 */
final class RecordedVideo {
    private static final int RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = CHANNELS * 2;
    private static final int QUIET = 320; // 16-bit amplitude, ~-40dBFS
    private static final long PAD_FRAMES = RATE / 5L;
    private static final long MIN_SOUND_FRAMES = RATE * 5L;
    private static final long MAX_TRIM_FRAMES = RATE * 25L;

    static File folder(Context c) {
        File dir = new File(c.getExternalFilesDir(null), "recorded-videos");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IllegalStateException("Cannot create private recording folder");
        return dir;
    }

    static File song(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}"))
            throw new IllegalArgumentException("Invalid video ID");
        return new File(folder(c), id + ".mp4");
    }

    static boolean saved(Context c, String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}") &&
                song(c, id).isFile() && song(c, id).length() > 4096;
    }

    static Uri uri(Context c, String id) { return Uri.fromFile(song(c, id)); }

    static boolean delete(Context c, String id) {
        File file = song(c,id);
        return !file.exists() || file.delete();
    }

    static final class Result {
        final long durationMs, skippedBeginningMs, skippedEndMs;
        Result(long durationMs, long skippedBeginningMs, long skippedEndMs) {
            this.durationMs=durationMs;
            this.skippedBeginningMs=skippedBeginningMs;
            this.skippedEndMs=skippedEndMs;
        }
    }
    private static final class Edges {
        final long startFrame,endFrame,totalFrames;
        Edges(long s,long e,long t) {startFrame=s;endFrame=e;totalFrames=t;}
    }

    static Result finish(Context c, String id, File screenMp4, File pcm, long audioDelayMs)
            throws IOException {
        Edges edges = edges(pcm);
        File dst = song(c,id);
        File aac = new File(folder(c), id + ".audio.working.m4a");
        File combined = new File(folder(c), id + ".video.working.mp4");
        if (aac.exists() && !aac.delete()) throw new IOException("Cannot reset temporary audio");
        if (combined.exists() && !combined.delete()) throw new IOException("Cannot reset temporary video");
        try {
            encodeAac(pcm, aac, edges);
            combine(screenMp4, aac, combined, Math.max(0,audioDelayMs),
                    edges.startFrame*1000000L/RATE,
                    (edges.endFrame-edges.startFrame)*1000000L/RATE);
            if (combined.length() < 4096) throw new IOException("Output MP4 is too small");
            if (dst.exists() && !dst.delete()) throw new IOException("Cannot replace old video");
            if (!combined.renameTo(dst)) throw new IOException("Cannot save MP4");
            return new Result((edges.endFrame-edges.startFrame)*1000/RATE,
                    edges.startFrame*1000/RATE,
                    (edges.totalFrames-edges.endFrame)*1000/RATE);
        } finally {
            aac.delete();
            combined.delete();
        }
    }

    private static Edges edges(File pcm) throws IOException {
        long frames = pcm.length()/BYTES_PER_FRAME;
        if (frames < MIN_SOUND_FRAMES) throw new IOException("ההקלטה קצרה מדי");
        long first=-1,last=-1,at=0;
        try (RandomAccessFile input=new RandomAccessFile(pcm,"r")) {
            byte[] buffer=new byte[32768];int size;
            while ((size=input.read(buffer))>0) {
                for (int n=0;n+3<size;n+=4) {
                    int l=(short)((buffer[n]&255)|(buffer[n+1]<<8));
                    int r=(short)((buffer[n+2]&255)|(buffer[n+3]<<8));
                    if (Math.max(Math.abs(l),Math.abs(r))>QUIET) {
                        if (first<0)first=at;
                        last=at;
                    }
                    at++;
                }
            }
        }
        if (last < 0 || last-first < MIN_SOUND_FRAMES)
            throw new IOException("השמע לא נקלט או קצר מדי");
        long start = first >= RATE ? Math.max(0,Math.min(first-PAD_FRAMES,MAX_TRIM_FRAMES)) : 0;
        long end = frames-last >= RATE ?
                Math.min(frames,Math.max(last+PAD_FRAMES,frames-MAX_TRIM_FRAMES)) : frames;
        if (end-start<MIN_SOUND_FRAMES) {start=0;end=frames;}
        return new Edges(start,end,frames);
    }

    private static void encodeAac(File pcm, File destination, Edges edges) throws IOException {
        MediaCodec encoder=null;
        MediaMuxer muxer=null;
        try (RandomAccessFile input=new RandomAccessFile(pcm,"r")) {
            MediaFormat format=MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,RATE,CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE,160000);
            encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer=new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            input.seek(edges.startFrame*BYTES_PER_FRAME);
            long nextFrame=0,remaining=(edges.endFrame-edges.startFrame);
            boolean inputDone=false,outputDone=false,muxStarted=false;
            int outputTrack=-1,spins=0;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            byte[] tmp=new byte[16384];
            while (!outputDone) {
                if (++spins>200000) throw new IOException("AAC encoding did not finish");
                if (!inputDone) {
                    int index=encoder.dequeueInputBuffer(10000);
                    if(index>=0){
                        ByteBuffer slot=encoder.getInputBuffer(index);
                        if(slot==null)throw new IOException("Missing encoder input");
                        slot.clear();
                        int bytes=(int)Math.min(remaining*BYTES_PER_FRAME,
                                Math.min((slot.capacity()/BYTES_PER_FRAME)*BYTES_PER_FRAME,tmp.length));
                        long timeUs=nextFrame*1000000L/RATE;
                        if(bytes<=0) {
                            encoder.queueInputBuffer(index,0,0,timeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone=true;
                        } else {
                            int got=input.read(tmp,0,bytes);
                            if(got<=0)throw new IOException("PCM ended unexpectedly");
                            got-=got%BYTES_PER_FRAME;
                            slot.put(tmp,0,got);
                            encoder.queueInputBuffer(index,0,got,timeUs,0);
                            long n=got/BYTES_PER_FRAME;
                            nextFrame+=n;remaining-=n;
                        }
                    }
                }
                int index=encoder.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if(muxStarted)throw new IOException("Repeated AAC output format");
                    outputTrack=muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();muxStarted=true;
                }else if(index>=0){
                    ByteBuffer bytes=encoder.getOutputBuffer(index);
                    if(bytes==null)throw new IOException("Missing encoded AAC buffer");
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0){
                        if(!muxStarted)throw new IOException("AAC output before format");
                        bytes.position(info.offset);bytes.limit(info.offset+info.size);
                        muxer.writeSampleData(outputTrack,bytes,info);
                    }
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.releaseOutputBuffer(index,false);
                }
            }
        } finally {
            if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}
            if(muxer!=null){try{muxer.stop();}catch(Exception ignored){}muxer.release();}
        }
    }

    private static void combine(File silentVideo,File aacFile,File output,
                                long delayMs,long trimStartUs,long audioDurationUs)
            throws IOException {
        MediaExtractor video=new MediaExtractor(),audio=new MediaExtractor();
        MediaMuxer muxer=null;
        try {
            video.setDataSource(silentVideo.getAbsolutePath());
            audio.setDataSource(aacFile.getAbsolutePath());
            int vi=findTrack(video,"video/"), ai=findTrack(audio,"audio/");
            if(vi<0||ai<0)throw new IOException("Video or AAC audio track is missing");
            video.selectTrack(vi);audio.selectTrack(ai);
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt=muxer.addTrack(video.getTrackFormat(vi));
            int at=muxer.addTrack(audio.getTrackFormat(ai));
            muxer.start();

            // The screen recorder starts before playback audio capture.
            // Preserve the preceding sync frame and shift audio timestamps
            // so the first musical attack stays aligned with the video.
            long wantedUs=delayMs*1000+trimStartUs;
            video.seekTo(wantedUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long baseUs=video.getSampleTime();
            if(baseUs<0)throw new IOException("No video frames after seeking");
            long audioLagUs=Math.max(0,wantedUs-baseUs);
            long videoEndUs=wantedUs+audioDurationUs+250000L;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            ByteBuffer videoBuf=ByteBuffer.allocateDirect(4*1024*1024);
            ByteBuffer audioBuf=ByteBuffer.allocateDirect(512*1024);
            long prevVideo=-1,prevAudio=-1;
            boolean vDone=false,aDone=false;
            while(!vDone||!aDone) {
                long vtUs=vDone?Long.MAX_VALUE:video.getSampleTime();
                long atUs=aDone?Long.MAX_VALUE:audio.getSampleTime();
                if(vtUs<0||vtUs>videoEndUs)vDone=true;
                if(atUs<0||atUs>audioDurationUs+250000L)aDone=true;
                if(vDone&&aDone)break;
                boolean doVideo=!vDone&&(aDone||vtUs-baseUs<=atUs+audioLagUs);
                MediaExtractor extractor=doVideo?video:audio;
                ByteBuffer buffer=doVideo?videoBuf:audioBuf;
                buffer.clear();
                int size=extractor.readSampleData(buffer,0);
                if(size<0) {
                    if(doVideo)vDone=true;else aDone=true;
                    continue;
                }
                long timeUs=doVideo?vtUs-baseUs:atUs+audioLagUs;
                if(timeUs<0)timeUs=0;
                if(doVideo){
                    timeUs=Math.max(timeUs,prevVideo+1);
                    prevVideo=timeUs;
                }else{
                    timeUs=Math.max(timeUs,prevAudio+1);
                    prevAudio=timeUs;
                }
                info.set(0,size,timeUs,extractor.getSampleFlags());
                muxer.writeSampleData(doVideo?vt:at,buffer,info);
                extractor.advance();
            }
        } finally {
            if(muxer!=null){try{muxer.stop();}catch(Exception ignored){}muxer.release();}
            video.release();audio.release();
        }
    }

    private static int findTrack(MediaExtractor extractor,String prefix) {
        for(int i=0;i<extractor.getTrackCount();i++){
            String type=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if(type!=null&&type.startsWith(prefix))return i;
        }
        return -1;
    }
}
