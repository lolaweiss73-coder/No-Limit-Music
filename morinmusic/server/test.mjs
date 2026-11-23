import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const temp=await fsp.mkdtemp(path.join(os.tmpdir(),'morinmusic-test-'));
const token='integration-test-token-strong-enough';
const port=await new Promise((resolve,reject)=>{
  const socket=net.createServer();
  socket.once('error',reject);
  socket.listen(0,'127.0.0.1',()=>{const n=socket.address().port;socket.close(()=>resolve(n));});
});
const address='http://127.0.0.1:'+port;
const child=spawn(process.execPath,[fileURLToPath(new URL('./index.mjs',import.meta.url))],{
  env:{...process.env,PORT:String(port),MORIN_MEDIA_DIR:temp,MORIN_UPLOAD_TOKEN:token},stdio:'pipe'
});
let output='';child.stderr.on('data',d=>output+=d.toString());
try{
  let ready=false;
  for(let i=0;i<80;i++){
    try{const response=await fetch(address+'/health');if(response.ok){ready=true;break;}}
    catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.ok(ready,'server failed to start '+output);
  let response=await fetch(address+'/api/catalog');
  assert.equal(response.status,401);
  const id='aBc12345-_Z';
  const bytes=Buffer.alloc(20000);for(let i=0;i<bytes.length;i++)bytes[i]=i%251;
  for(const kind of ['audio','video']){
    response=await fetch(address+'/api/media/'+id+'/'+kind+'?title='+encodeURIComponent('שיר לדוגמה'),{
      method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/octet-stream'},body:bytes
    });
    assert.equal(response.status,201,await response.text());
  }
  response=await fetch(address+'/playlists.json');
  assert.equal(response.status,200);
  const playlists=await response.json();
  assert.equal(playlists[0].tracks[0].title,'שיר לדוגמה');
  assert.equal(playlists[0].tracks[0].src,'media/'+id+'.m4a');
  assert.equal(playlists[0].tracks[0].videoSrc,'media/'+id+'.mp4');
  response=await fetch(address+'/media/'+id+'.m4a',{headers:{Range:'bytes=10-19'}});
  assert.equal(response.status,206);
  assert.equal(Buffer.from(await response.arrayBuffer()).length,10);
  response=await fetch(address+'/media/'+id+'.mp4',{method:'HEAD'});
  assert.equal(response.status,200);
  assert.equal(response.headers.get('content-length'),'20000');
  response=await fetch(address+'/api/catalog',{headers:{Authorization:'Bearer '+token}});
  assert.equal(response.status,200);
  assert.equal((await response.json()).tracks[id].audio,true);
  response=await fetch(address+'/api/media/../audio',{method:'POST',headers:{Authorization:'Bearer '+token},body:bytes});
  assert.notEqual(response.status,201);
  console.log('PASS: authenticated streaming upload, catalog, ranges, HEAD, path validation');
} finally {
  child.kill('SIGTERM');
  await fsp.rm(temp,{recursive:true,force:true});
}
