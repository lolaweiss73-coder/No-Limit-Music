package il.nolimits.music;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-supplied, per-track local subtitles. Does not download YouTube captions. */
final class LyricsStore {
    static final class Cue {
        final long start,end;
        final String text;
        Cue(long s,long e,String t){start=s;end=e;text=t;}
    }
    private static final Pattern LRC=Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern CLOCK=Pattern.compile("(\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{1,3}");
    private static final int MAX_BYTES=500_000;

    private static File file(Context context,String id){
        if(id==null||!id.matches("[A-Za-z0-9_-]{11}"))
            throw new IllegalArgumentException("Invalid track id");
        return new File(new File(context.getFilesDir(),"lyrics"),id+".txt");
    }

    static void importForTrack(Context context,String id,InputStream stream)throws Exception {
        if(stream==null)throw new IllegalArgumentException("Missing subtitle input");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];int size=0,n;
        while((n=stream.read(buffer))!=-1){
            size+=n;
            if(size>MAX_BYTES)throw new IllegalArgumentException("Subtitle file too big");
            output.write(buffer,0,n);
        }
        String content=new String(output.toByteArray(),StandardCharsets.UTF_8);
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        if(content.trim().isEmpty()||parse(content).isEmpty())
            throw new IllegalArgumentException("Empty or invalid subtitle data");
        File target=file(context,id);
        File parent=target.getParentFile();
        if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())
            throw new IllegalStateException("Cannot create subtitle folder");
        try(FileOutputStream sink=new FileOutputStream(target)){
            sink.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static ArrayList<Cue> load(Context context,String id){
        ArrayList<Cue> result=new ArrayList<>();
        try{
            File source=file(context,id);
            if(!source.isFile()||source.length()>MAX_BYTES)return result;
            byte[] bytes=new byte[(int)source.length()];
            try(FileInputStream input=new FileInputStream(source)){
                int offset=0,n;
                while(offset<bytes.length&&(n=input.read(bytes,offset,bytes.length-offset))>0)
                    offset+=n;
            }
            return parse(new String(bytes,StandardCharsets.UTF_8));
        }catch(Exception ignored){return result;}
    }

    static String at(ArrayList<Cue> cues,long millis){
        for(Cue c:cues)if(millis>=c.start&&millis<c.end)return c.text;
        return "";
    }

    private static long toMs(String stamp){
        String[] parts=stamp.trim().replace(',','.').split(":");
        try{
            if(parts.length==2)
                return Long.parseLong(parts[0])*60000+
                        Math.round(Double.parseDouble(parts[1])*1000);
            if(parts.length==3)
                return Long.parseLong(parts[0])*3600000+
                        Long.parseLong(parts[1])*60000+
                        Math.round(Double.parseDouble(parts[2])*1000);
        }catch(Exception ignored){}
        return -1;
    }

    static ArrayList<Cue> parse(String raw){
        ArrayList<Cue> cues=new ArrayList<>();
        if(raw==null)return cues;
        String[] lines=raw.replace("\r","").split("\n",-1);
        boolean timed=false;
        // LRC: one line may have several timestamps.
        for(String line:lines){
            Matcher tag=LRC.matcher(line);
            ArrayList<Long> times=new ArrayList<>();
            int end=0;
            while(tag.find()){
                long ms=Long.parseLong(tag.group(1))*60000+
                        Long.parseLong(tag.group(2))*1000;
                if(tag.group(3)!=null){
                    String fractions=tag.group(3);
                    ms+=Long.parseLong(fractions)*
                            (fractions.length()==1?100:fractions.length()==2?10:1);
                }
                times.add(ms);end=tag.end();
            }
            if(times.isEmpty())continue;
            String lyric=line.substring(end).trim();
            if(lyric.isEmpty())continue;
            for(long ms:times)cues.add(new Cue(ms,Long.MAX_VALUE,lyric));
            timed=true;
        }
        if(timed){
            cues.sort(Comparator.comparingLong(c->c.start));
            ArrayList<Cue> bounded=new ArrayList<>();
            for(int i=0;i<cues.size();i++){
                Cue c=cues.get(i);
                long end=i+1<cues.size()?Math.max(c.start+1,cues.get(i+1).start):Long.MAX_VALUE;
                bounded.add(new Cue(c.start,end,c.text));
            }
            return bounded;
        }
        // SRT/WebVTT: a timestamp range followed by one or more text lines.
        for(int i=0;i<lines.length;i++){
            String line=lines[i];
            if(!line.contains("-->"))continue;
            String[] ends=line.split("-->",2);
            Matcher startMatcher=CLOCK.matcher(ends[0]);
            Matcher endMatcher=CLOCK.matcher(ends[1]);
            if(!startMatcher.find()||!endMatcher.find())continue;
            long start=toMs(startMatcher.group()),end=toMs(endMatcher.group());
            if(start<0||end<=start)continue;
            StringBuilder body=new StringBuilder();
            while(++i<lines.length&&!lines[i].trim().isEmpty()){
                if(body.length()>0)body.append('\n');
                body.append(lines[i].trim());
            }
            if(body.length()>0){
                cues.add(new Cue(start,end,body.toString()));
                timed=true;
            }
        }
        if(timed){cues.sort(Comparator.comparingLong(c->c.start));return cues;}
        // Plain text is shown as one un-timed caption.
        String plain=raw.trim();
        if(!plain.isEmpty()&&!plain.startsWith("WEBVTT"))
            cues.add(new Cue(0,Long.MAX_VALUE,plain.substring(0,Math.min(plain.length(),5000))));
        return cues;
    }
}
