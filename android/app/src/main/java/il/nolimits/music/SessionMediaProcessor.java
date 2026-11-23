package il.nolimits.music;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Remux encoded tracks without reencoding. Originals are preserved.
 * Trimming video starts at the next sync frame to avoid broken H264 GOPs.
 * This is general-purpose processing for captures in the device's private library.
 */
public final class SessionMediaProcessor {
    private SessionMediaProcessor(){}
    private static int select(MediaExtractor extractor,boolean video){
        for(int i=0;i<extractor.getTrackCount();i++){
            String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if(mime!=null&&mime.startsWith(video?"video/":"audio/"))return i;
        }
        return -1;
    }
    public static void combine(File video,File audio,File output)throws IOException {
        process(video,audio,output,0,Long.MAX_VALUE,false,true);
    }
    public static void extractAudio(File merged,File output,long startMs,long endMs)throws IOException {
        process(merged,null,output,startMs,endMs,true,false);
    }
    public static void extractVideoClip(File merged,File output,long startMs,long endMs)throws IOException {
        process(merged,null,output,startMs,endMs,false,false);
    }
    private static class Input {
        final MediaExtractor extractor;
        final int track;
        final int outputTrack;
        final long originalStartUs;
        boolean finished;
        Input(MediaExtractor e,int t,int o,long start){
            extractor=e;track=t;outputTrack=o;originalStartUs=start;
        }
    }
    private static void process(File videoFile,File separateAudio,File output,long startMs,long endMs,
                                boolean audioOnly,boolean combine)throws IOException{
        if(videoFile==null||!videoFile.isFile())throw new IOException("אין הקלטה לעיבוד");
        if(startMs<0||endMs<=startMs)throw new IOException("טווח חיתוך לא תקין");
        long startUs=startMs*1000L;
        long endUs=endMs==Long.MAX_VALUE?Long.MAX_VALUE:endMs*1000L;
        MediaExtractor v=new MediaExtractor(),a=null;
        MediaMuxer mux=null;boolean started=false;
        try{
            v.setDataSource(videoFile.getAbsolutePath());
            int videoTrack=audioOnly?-1:select(v,true);
            int localAudioTrack=(!combine)?select(v,false):-1;
            if(!audioOnly&&videoTrack<0)throw new IOException("לקובץ אין מסלול וידאו");
            if(combine){
                if(separateAudio!=null&&separateAudio.isFile()){
                    a=new MediaExtractor();a.setDataSource(separateAudio.getAbsolutePath());
                }
            }else if(localAudioTrack>=0){
                a=new MediaExtractor();a.setDataSource(videoFile.getAbsolutePath());
            }
            int audioTrack=a==null?-1:select(a,false);
            if(combine&&!audioOnly&&audioTrack<0){
                // Keep video even when source app disallows audio capture.
                a=null;
            }
            if(audioOnly&&audioTrack<0){
                if(localAudioTrack>=0&&a==null){
                    a=new MediaExtractor();a.setDataSource(videoFile.getAbsolutePath());
                    audioTrack=select(a,false);
                }
                if(audioTrack<0)throw new IOException("הקובץ אינו כולל שמע");
            }
            // No source is modified; output is a fresh MP4 or M4A.
            if(output.exists()&&!output.delete())throw new IOException("אי אפשר להחליף קובץ יעד");
            mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            ArrayList<Input> inputs=new ArrayList<>(2);
            long actualStartUs=startUs;
            if(videoTrack>=0){
                v.selectTrack(videoTrack);
                if(startUs>0){
                    v.seekTo(startUs,MediaExtractor.SEEK_TO_NEXT_SYNC);
                    long vTime=v.getSampleTime();
                    if(vTime<0||vTime>=endUs)throw new IOException("אין פריימים בטווח המבוקש");
                    actualStartUs=vTime;
                }
                inputs.add(new Input(v,videoTrack,mux.addTrack(v.getTrackFormat(videoTrack)),actualStartUs));
            }
            if(a!=null&&audioTrack>=0){
                a.selectTrack(audioTrack);
                long min=videoTrack>=0?actualStartUs:startUs;
                if(min>0)a.seekTo(min,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while(a.getSampleTime()>=0&&a.getSampleTime()<min)a.advance();
                inputs.add(new Input(a,audioTrack,mux.addTrack(a.getTrackFormat(audioTrack)),min));
            }
            if(inputs.isEmpty())throw new IOException("אין ערוצי מדיה להפקה");
            mux.start();started=true;
            ByteBuffer buffer=ByteBuffer.allocateDirect(4*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(true){
                Input next=null;long earliest=Long.MAX_VALUE;
                for(Input in:inputs){
                    long pts=in.finished?-1:in.extractor.getSampleTime();
                    if(pts<0||pts>=endUs){in.finished=true;continue;}
                    if(pts<earliest){next=in;earliest=pts;}
                }
                if(next==null)break;
                buffer.clear();
                int size=next.extractor.readSampleData(buffer,0);
                if(size<0){next.finished=true;continue;}
                if(size>buffer.capacity())throw new IOException("דגימה גדולה מכושר העיבוד");
                info.set(0,size,Math.max(0,earliest-actualStartUs),next.extractor.getSampleFlags());
                mux.writeSampleData(next.outputTrack,buffer,info);
                next.extractor.advance();
            }
        }catch(Exception error){
            throw error instanceof IOException?(IOException)error:new IOException(error.getMessage(),error);
        }finally{
            if(mux!=null){try{if(started)mux.stop();}catch(Exception ignored){}try{mux.release();}catch(Exception ignored){}}
            try{v.release();}catch(Exception ignored){}
            if(a!=null)try{a.release();}catch(Exception ignored){}
        }
    }
    public static int splitMarkedSession(File folder)throws IOException{
        File marks=new File(folder,"song-marks.jsonl");
        File merged=new File(folder,"session-video-audio.mp4");
        if(!merged.isFile())throw new IOException("קודם יש לסיים הקלטה משולבת");
        if(!marks.isFile())throw new IOException("לא נמצאו סימוני שירים בסשן");
        File clips=new File(folder,"Clips");
        if(!clips.isDirectory()&&!clips.mkdirs())throw new IOException("אי אפשר ליצור תיקיית קליפים");
        ArrayList<JSONObject> all=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(marks),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){
                try{all.add(new JSONObject(line));}catch(Exception ignored){}
            }
        }
        int count=0;JSONObject active=null;
        for(JSONObject mark:all){
            String kind=mark.optString("kind");
            if("start".equals(kind)){
                if(active!=null)count+=trySlice(folder,clips,merged,active,mark.optLong("ms"));
                active=mark;
            }else if("end".equals(kind)&&active!=null){
                if(mark.optString("videoId").equals(active.optString("videoId"))){
                    count+=trySlice(folder,clips,merged,active,mark.optLong("ms"));
                    active=null;
                }
            }
        }
        return count;
    }
    private static int trySlice(File folder,File clips,File merged,JSONObject start,long endMs)throws IOException{
        long begin=start.optLong("ms"),end=endMs;
        if(end-begin<2000)return 0;
        String id=start.optString("videoId","unknown").replaceAll("[^A-Za-z0-9_-]","_");
        String filename=String.format(Locale.US,"%03d-%s",clips.listFiles()==null?0:clips.listFiles().length/2,id);
        File video=new File(clips,filename+".mp4");
        File audio=new File(clips,filename+".m4a");
        extractVideoClip(merged,video,begin,end);
        try{extractAudio(merged,audio,begin,end);}catch(IOException e){
            // Video can still be usable when Android blocked internal audio capture.
        }
        return 1;
    }
}
