import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import {timingSafeEqual, randomUUID} from 'node:crypto';
import {pipeline} from 'node:stream/promises';
import {Transform} from 'node:stream';

const root=path.resolve(process.env.DATA_DIR||'./server-data');
const media=path.join(root,'media');
const catalog=path.join(root,'library.json');
const syncState=path.join(root,'youtube-sync.json');
const refreshIntervalMs=15*60*1000;
const source=process.env.SOURCE_PLAYLISTS_URL||'https://avi-music-player.lolaweiss73.chatgpt.site/playlists.json';
const key=process.env.YOUTUBE_API_KEY||'';
const channel=process.env.YOUTUBE_CHANNEL_ID||'';
const token=process.env.UPLOAD_TOKEN||'';
const max=400*1024*1024; // recording includes video and sound
const idValid=id=>/^[A-Za-z0-9_-]{11}$/.test(id);
let inflight=null;
let lastRefreshFailure=null;

function reply(res,status,obj){
  const body=Buffer.from(JSON.stringify(obj));
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8',
    'Content-Length':body.length,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(body);
}
function auth(req){
  const a=Buffer.from(req.headers.authorization||''),b=Buffer.from('Bearer '+token);
  return !!token && a.length===b.length && timingSafeEqual(a,b);
}
async function read(file){
  try{return JSON.parse(await fsp.readFile(file,'utf8'));}
  catch(e){if(e.code==='ENOENT')return null;throw e;}
}
function clean(data){
  if(!Array.isArray(data))throw Error('Invalid playlist JSON');
  return data.filter(p=>p&&typeof p.id==='string'&&typeof p.title==='string'&&Array.isArray(p.tracks))
    .map(p=>({id:p.id,title:p.title,tracks:p.tracks
      .filter(t=>t&&idValid(t.youtubeId||'')).map(t=>({
        youtubeId:t.youtubeId,title:t.title||t.youtubeId,src:t.src||null
      }))}));
}
async function youtube(endpoint,params){
  const u=new URL('https://www.googleapis.com/youtube/v3/'+endpoint);
  Object.entries({...params,key}).forEach(([k,v])=>u.searchParams.set(k,v));
  const r=await fetch(u,{signal:AbortSignal.timeout(30000)});
  if(!r.ok)throw Error('YouTube API returned '+r.status);
  return r.json();
}
async function discover(){
  if(!key||!channel)return null;
  const lists=[];let cursor;
  do{
    const data=await youtube('playlists',{part:'snippet',channelId:channel,maxResults:50,
      ...(cursor?{pageToken:cursor}:{})});
    for(const x of data.items||[])if(x.id&&x.snippet?.title)
      lists.push({id:x.id,title:x.snippet.title,tracks:[]});
    cursor=data.nextPageToken;
    if(lists.length>500)throw Error('Too many playlists');
  }while(cursor);
  for(const p of lists){
    let page;
    do{
      const data=await youtube('playlistItems',{part:'snippet',playlistId:p.id,maxResults:50,
        ...(page?{pageToken:page}:{})});
      for(const x of data.items||[]){
        const id=x.snippet?.resourceId?.videoId,t=x.snippet?.title;
        if(idValid(id||'')&&t&&t!=='Private video'&&t!=='Deleted video')
          p.tracks.push({youtubeId:id,title:t,src:null});
      }
      page=data.nextPageToken;
      if(p.tracks.length>5000)throw Error('Playlist too large');
    }while(page);
  }
  return lists;
}
function merge(old,updated){
  if(!updated)return old;
  const known=new Map(old.map(p=>[p.id,p])),seen=new Set(updated.map(p=>p.id));
  const result=updated.map(p=>{
    const sources=new Map((known.get(p.id)?.tracks||[]).map(t=>[t.youtubeId,t.src]));
    return {...p,tracks:p.tracks.map(t=>({...t,src:sources.get(t.youtubeId)||null}))};
  });
  for(const p of old)if(!seen.has(p.id))result.push(p);
  return result;
}
async function overlay(library){
  const videos=new Set((await fsp.readdir(media)).filter(x=>x.endsWith('.mp4')));
  return library.map(p=>({...p,tracks:p.tracks.map(t=>({
    ...t,src:videos.has(t.youtubeId+'.mp4')&&!(t.src||'').startsWith('media/')
        ?'cloud/'+t.youtubeId+'.mp4':t.src
  }))}));
}
async function getLibrary(sync){
  if(inflight)return inflight;
  inflight=(async()=>{
    const old=await read(catalog);
    let current=old?clean(old):[];
    if(!old||sync){
      const response=await fetch(source,{signal:AbortSignal.timeout(20000)});
      if(!response.ok)throw Error('Website catalog HTTP '+response.status);
      const baseline=clean(await response.json());
      const audio=new Map(current.flatMap(p=>p.tracks.filter(t=>t.src).map(t=>[t.youtubeId,t.src])));
      const oldIds=new Set(baseline.map(p=>p.id));
      current=baseline.map(p=>({...p,tracks:p.tracks.map(t=>({
        ...t,src:t.src||audio.get(t.youtubeId)||null
      }))}));
      for(const p of old||[])if(!oldIds.has(p.id))current.push(p);
    }
    const state=(await read(syncState))||{};
    const expired=Date.now()-Number(state.updatedAt||0)>refreshIntervalMs;
    if(sync&&!key)throw Error('YouTube sync not configured: missing YOUTUBE_API_KEY');
    if(sync&&!channel)throw Error('YouTube sync not configured: missing YOUTUBE_CHANNEL_ID');
    if((sync||expired)&&(key&&channel)){
      try {
        const discovered=await discover();
        current=merge(current,discovered);
        lastRefreshFailure=null;
        await fsp.writeFile(syncState+'.new',JSON.stringify({
          updatedAt:Date.now(),playlistCount:discovered.length
        }));
        await fsp.rename(syncState+'.new',syncState);
      }catch(e){
        lastRefreshFailure=String(e.message||e);
        if(sync)throw e; // do not tell the user a manual refresh succeeded
        // The last known library stays available during transient API failures.
        console.warn('Automatic YouTube sync failed; serving cache:',lastRefreshFailure);
      }
    }
    current=await overlay(current);
    await fsp.writeFile(catalog+'.new',JSON.stringify(current));
    await fsp.rename(catalog+'.new',catalog);
    return current;
  })();
  try{return await inflight;}finally{inflight=null;}
}
async function put(req,res,id){
  if(!auth(req))return reply(res,401,{error:'Not authorized'});
  if(String(req.headers['content-type']||'').split(';')[0].toLowerCase()!=='video/mp4')
    return reply(res,415,{error:'MP4 required'});
  const length=Number(req.headers['content-length']);
  if(!Number.isInteger(length)||length<4096||length>max)
    return reply(res,413,{error:'Invalid file size'});
  const tmp=path.join(media,id+'.'+randomUUID()+'.upload');
  let bytes=0;
  const limiter=new Transform({transform(chunk,enc,done){
    bytes+=chunk.length;
    done(bytes>max?Error('Upload too large'):null,chunk);
  }});
  try{
    await pipeline(req,limiter,fs.createWriteStream(tmp,{flags:'wx',mode:0o600}));
    const fd=await fsp.open(tmp,'r'),hdr=Buffer.alloc(12);
    try{await fd.read(hdr,0,12,0);}finally{await fd.close();}
    const boxLength=hdr.readUInt32BE(0);
    if(bytes!==length||hdr.toString('ascii',4,8)!=='ftyp'||
      boxLength<12||boxLength>bytes)
      return reply(res,422,{error:'Invalid or incomplete MP4 header'});
    await fsp.rename(tmp,path.join(media,id+'.mp4'));
    const current=await read(catalog);
    if(current){
      const revised=await overlay(clean(current));
      await fsp.writeFile(catalog+'.new',JSON.stringify(revised));
      await fsp.rename(catalog+'.new',catalog);
    }
    return reply(res,201,{ok:true,id,bytes});
  }catch(e){
    return reply(res,bytes>max?413:500,{error:'Upload failed'});
  }finally{await fsp.rm(tmp,{force:true}).catch(()=>{});}
}
async function stream(req,res,id){
  if(!auth(req))return reply(res,401,{error:'Not authorized'});
  const file=path.join(media,id+'.mp4');let stat;
  try{stat=await fsp.stat(file);}catch{return reply(res,404,{error:'Not found'});}
  let begin=0,end=stat.size-1,status=200;
  const range=req.headers.range;
  if(range){
    const m=/^bytes=(\d*)-(\d*)$/.exec(range);
    if(!m)return reply(res,416,{error:'Invalid Range'});
    if(m[1]===''){
      let n=Number(m[2]);if(!Number.isInteger(n)||n<1)return reply(res,416,{error:'Bad Range'});
      begin=Math.max(0,stat.size-n);
    }else{
      begin=Number(m[1]);if(m[2]!=='')end=Math.min(end,Number(m[2]));
    }
    if(!Number.isSafeInteger(begin)||!Number.isSafeInteger(end)||begin>end||begin>=stat.size)
      return reply(res,416,{error:'Out of range'});
    status=206;
  }
  const headers={'Content-Type':'video/mp4','Accept-Ranges':'bytes',
    'Content-Length':end-begin+1,'Cache-Control':'private, max-age=600',
    'X-Content-Type-Options':'nosniff'};
  if(status===206)headers['Content-Range']='bytes '+begin+'-'+end+'/'+stat.size;
  res.writeHead(status,headers);
  if(req.method==='HEAD')return res.end();
  fs.createReadStream(file,{start:begin,end}).pipe(res);
}
await fsp.mkdir(media,{recursive:true});
http.createServer(async(req,res)=>{
  try{
    const url=new URL(req.url||'/', 'https://localhost');
    if(req.method==='GET'&&url.pathname==='/healthz'){
      const latest=await read(syncState);
      return reply(res,200,{
        ok:true,youtubeSyncConfigured:!!(key&&channel),
        lastYouTubeSyncAt:latest?.updatedAt||null,
        youtubeSyncError:lastRefreshFailure
      });
    }
    if(req.method==='GET'&&url.pathname==='/api/library.json'){
      const sync=url.searchParams.get('sync')==='1';
      if(sync&&!auth(req))return reply(res,401,{error:'Sync requires authentication'});
      if(sync&&(!key||!channel))return reply(res,503,{
        error:'YouTube API key and channel ID must be configured for live playlist discovery'
      });
      return reply(res,200,await getLibrary(sync));
    }
    const m=/^\/api\/recordings\/([A-Za-z0-9_-]{11})\.mp4$/.exec(url.pathname);
    if(m){
      if(req.method==='PUT')return put(req,res,m[1]);
      if(req.method==='GET'||req.method==='HEAD')return stream(req,res,m[1]);
    }
    return reply(res,404,{error:'Unknown endpoint'});
  }catch(e){
    console.error(e.message);
    return reply(res,503,{error:'Service unavailable'});
  }
}).listen(Number(process.env.PORT||3000),'0.0.0.0',()=>console.log('No Limit cloud ready'));
