#!/usr/bin/env node
// MorinMusic media API. Node 20+, no third-party packages.
// Configure a PRIVATE MORIN_UPLOAD_TOKEN and persistent MORIN_MEDIA_DIR.
// This is source code only; not deployed to any domain.
import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';

const port=Number(process.env.PORT||8787);
const root=path.resolve(process.env.MORIN_MEDIA_DIR||'./morinmusic-media');
const secret=process.env.MORIN_UPLOAD_TOKEN||'';
if(secret.length<24){console.error('Set MORIN_UPLOAD_TOKEN with at least 24 characters');process.exit(1);}
const mediaDir=path.join(root,'media');
await fsp.mkdir(mediaDir,{recursive:true});
const indexFile=path.join(root,'index.json');
let index={version:1,tracks:{}};
try{const old=JSON.parse(await fsp.readFile(indexFile,'utf8'));if(old&&old.tracks)index=old;}
catch(e){if(e.code!=='ENOENT')throw e;}
const MAX_UPLOAD=8*1024*1024*1024;
const isId=(s)=>/^[A-Za-z0-9_-]{11}$/.test(s);
let saveQueue=Promise.resolve();
const reply=(res,code,obj)=>{
  if(res.headersSent)return;
  res.writeHead(code,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});
  res.end(JSON.stringify(obj));
};
const authorized=(req)=>{
  const header=req.headers.authorization||'';
  const value=header.startsWith('Bearer ')?header.slice(7):'';
  const a=Buffer.from(value),b=Buffer.from(secret);
  return a.length===b.length&&crypto.timingSafeEqual(a,b);
};
async function saveIndex(){
  const tmp=indexFile+'.'+crypto.randomBytes(6).toString('hex')+'.tmp';
  await fsp.writeFile(tmp,JSON.stringify(index,null,2),{mode:0o600});
  await fsp.rename(tmp,indexFile);
}
function playlists(){
  const tracks=Object.entries(index.tracks).filter(([id,data])=>isId(id)&&data.audio);
  return [{title:'הספרייה שלנו',tracks:tracks.map(([id,data])=>({
    youtubeId:id,title:data.title||id,src:'media/'+id+'.m4a',
    ...(data.video?{videoSrc:'media/'+id+'.mp4'}:{})
  }))}];
}
function streamFile(req,res,id,ext){
  const name=path.join(mediaDir,id+'.'+ext);
  let stat;try{stat=fs.statSync(name);}catch{return reply(res,404,{error:'file not found'});}
  if(!stat.isFile())return reply(res,404,{error:'file not found'});
  let begin=0,last=stat.size-1,code=200;
  if(req.headers.range){
    const match=/^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
    if(!match)return reply(res,416,{error:'invalid range'});
    if(!match[1]&&match[2]){
      const suffix=Number(match[2]);
      if(!Number.isSafeInteger(suffix)||suffix<=0)return reply(res,416,{error:'invalid range'});
      begin=Math.max(0,stat.size-suffix);
    }else{
      begin=Number(match[1]||0);
      if(match[2])last=Math.min(stat.size-1,Number(match[2]));
    }
    if(!Number.isSafeInteger(begin)||begin>=stat.size||last<begin)return reply(res,416,{error:'range exceeds file'});
    code=206;
  }
  const headers={'content-type':ext==='mp4'?'video/mp4':ext==='mp3'?'audio/mpeg':'audio/mp4',
    'content-length':last-begin+1,'accept-ranges':'bytes','cache-control':'public, max-age=600',
    'x-content-type-options':'nosniff'};
  if(code===206)headers['content-range']='bytes '+begin+'-'+last+'/'+stat.size;
  res.writeHead(code,headers);
  if(req.method==='HEAD'){res.end();return;}
  fs.createReadStream(name,{start:begin,end:last}).on('error',()=>res.destroy()).pipe(res);
}
async function upload(req,res,id,kind,url){
  if(!authorized(req))return reply(res,401,{error:'invalid token'});
  const ext=kind==='audio'?'m4a':'mp4';
  if(Number(req.headers['content-length']||0)>MAX_UPLOAD)return reply(res,413,{error:'too large'});
  const tmp=path.join(mediaDir,'.'+id+'.'+crypto.randomBytes(7).toString('hex')+'.part');
  const dest=path.join(mediaDir,id+'.'+ext);
  let written=0;
  try{
    const limit=new Transform({transform(chunk,encoding,cb){
      written+=chunk.length;
      if(written>MAX_UPLOAD)cb(new Error('too large'));
      else cb(null,chunk);
    }});
    await pipeline(req,limit,fs.createWriteStream(tmp,{flags:'wx',mode:0o600}));
    if(written<128)throw new Error('file too short');
    await fsp.rename(tmp,dest);
    const title=String(url.searchParams.get('title')||id).replace(/[\p{Cc}\p{Cf}]/gu,'').slice(0,200);
    const old=index.tracks[id]||{createdAt:new Date().toISOString()};
    old.title=title;old[kind]=true;old.updatedAt=new Date().toISOString();
    index.tracks[id]=old;
    saveQueue=saveQueue.then(saveIndex);
    await saveQueue;
    reply(res,201,{ok:true,id,kind,bytes:written,src:'media/'+id+'.'+ext});
  }catch(e){
    await fsp.unlink(tmp).catch(()=>{});
    reply(res,/too large/.test(e.message)?413:500,{error:e.message});
  }
}
const server=http.createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    const p=url.pathname;
    if(p==='/'&&req.method==='GET'){
      res.writeHead(200,{'content-type':'text/html; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});
      return res.end('<!doctype html><html lang="he" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>MorinMusic</title><style>body{font-family:system-ui,sans-serif;background:#121a29;color:#f9fafb;max-width:680px;margin:8vh auto;padding:2rem}a{color:#a7f3d0}</style><h1>🎵 MorinMusic</h1><p>שרת המוזיקה החדש פעיל.</p><p><a href="/health">בדיקת בריאות</a> · <a href="/playlists.json">ספריית שירים</a></p></html>');
    }
    if(p==='/health'&&req.method==='GET')return reply(res,200,{ok:true,version:1});
    if(p==='/playlists.json'&&req.method==='GET')return reply(res,200,playlists());
    if(p==='/api/catalog'&&req.method==='GET'){
      if(!authorized(req))return reply(res,401,{error:'invalid token'});
      return reply(res,200,index);
    }
    const media=/^\/media\/([A-Za-z0-9_-]{11})\.(m4a|mp4|mp3)$/.exec(p);
    if(media&&['GET','HEAD'].includes(req.method))return streamFile(req,res,media[1],media[2]);
    const target=/^\/api\/media\/([A-Za-z0-9_-]{11})\/(audio|video)$/.exec(p);
    if(target&&req.method==='POST')return upload(req,res,target[1],target[2],url);
    return reply(res,404,{error:'unknown route'});
  }catch(e){reply(res,500,{error:e.message});}
});
server.requestTimeout=0; // reverse proxy must enforce rate limits and timeouts
server.listen(port,'0.0.0.0',()=>console.log('MorinMusic server listening at '+port));
process.on('SIGTERM',()=>server.close(()=>process.exit(0)));
