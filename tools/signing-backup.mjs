#!/usr/bin/env node
// Reusable Android signing-key escrow. Requires only Node.js 20+.
// Only encrypted JSON belongs in Git. Recovery secrets MUST stay outside Git.
import fs from 'node:fs';
import { randomBytes, scryptSync, createCipheriv, createDecipheriv, createHash } from 'node:crypto';

const fmt='OpenAI-github-signing-backup-v1';
const b64=(b)=>Buffer.from(b).toString('base64');
const from64=(s)=>Buffer.from(s,'base64');
const hash=(b)=>createHash('sha256').update(b).digest('hex');
const secureWrite=(file,data)=>fs.writeFileSync(file,data,{flag:'wx',mode:0o600});
const args=process.argv.slice(2);
const command=args.shift();
function usage(){
 throw new Error(
 'Usage: node tools/signing-backup.mjs encrypt input.zip output.encrypted.json private-recovery.txt applicationId projectName keyAlias\n'+
 '   or: node tools/signing-backup.mjs decrypt backup.encrypted.json output.zip [private-recovery.txt]\n'+
 'For CI set SIGNING_BACKUP_PASSPHRASE as a protected GitHub Actions repository secret.'
 );
}
function resolveSecret(recoveryFile){
 if(process.env.SIGNING_BACKUP_PASSPHRASE)return process.env.SIGNING_BACKUP_PASSPHRASE.trim();
 if(!recoveryFile)throw new Error('Missing SIGNING_BACKUP_PASSPHRASE or private recovery file');
 const text=fs.readFileSync(recoveryFile,'utf8').trim();
 const lines=text.split(/\r?\n/).map(x=>x.trim()).filter(Boolean);
 const i=lines.findIndex(x=>x.startsWith('Encryption recovery passphrase'));
 return i>=0?lines[i+1]||'':text;
}
if(command==='encrypt'){
 if(args.length!==6)usage();
 const [input,encryptedPath,recoveryPath,applicationId,project,keyAlias]=args;
 if(!/^[a-zA-Z][a-zA-Z0-9_.]+$/.test(applicationId))throw new Error('Invalid Android application ID');
 if(!project||!keyAlias)throw new Error('Project and alias are required');
 const plain=fs.readFileSync(input);
 if(!plain.subarray(0,2).equals(Buffer.from([0x50,0x4b])))throw new Error('Expected ZIP signing archive');
 const phrase=randomBytes(48).toString('base64url');
 const salt=randomBytes(16),iv=randomBytes(12);
 const aad=Buffer.from(project+'|'+applicationId+'|v1|signing-archive');
 const key=scryptSync(phrase,salt,32,{N:32768,r:8,p:1,maxmem:128*1024*1024});
 const cipher=createCipheriv('aes-256-gcm',key,iv);
 cipher.setAAD(aad);
 const ct=Buffer.concat([cipher.update(plain),cipher.final(),cipher.getAuthTag()]);
 const backup={
   format:fmt,applicationId,project,keyAlias,created:new Date().toISOString().slice(0,10),
   encryption:'AES-256-GCM',
   kdf:{algorithm:'scrypt',n:32768,r:8,p:1,saltBase64:b64(salt)},
   nonceBase64:b64(iv),aadBase64:b64(aad),ciphertextBase64:b64(ct),
   plaintextSha256:hash(plain),
   notes:'Recovery passphrase and unencrypted keystore must NOT be stored in Git.'
 };
 // Write the recovery secret first; abort if it already exists.
 secureWrite(recoveryPath,
  'PRIVATE SIGNING RECOVERY SECRET - NEVER PUT IN GIT\n'+
  'Application ID: '+applicationId+'\n'+
  'Encryption recovery passphrase (KEEP PRIVATE):\n'+phrase+'\n');
 secureWrite(encryptedPath,JSON.stringify(backup,null,2)+'\n');
 console.log('Encrypted signing backup created. SHA-256: '+hash(plain));
}else if(command==='decrypt'){
 if(args.length<2||args.length>3)usage();
 const [encryptedPath,outputZip,recoveryFile]=args;
 const meta=JSON.parse(fs.readFileSync(encryptedPath,'utf8'));
 if(meta.format!==fmt||meta.encryption!=='AES-256-GCM'||meta.kdf?.algorithm!=='scrypt')
   throw new Error('Unsupported encrypted signing backup');
 const {n,r,p}=meta.kdf;
 if(n!==32768||r!==8||p!==1)throw new Error('Unexpected KDF parameters');
 const phrase=resolveSecret(recoveryFile);
 if(!phrase)throw new Error('Empty recovery secret');
 const key=scryptSync(phrase,from64(meta.kdf.saltBase64),32,{N:n,r,p,maxmem:128*1024*1024});
 const payload=from64(meta.ciphertextBase64);
 if(payload.length<=16)throw new Error('Invalid ciphertext');
 const decipher=createDecipheriv('aes-256-gcm',key,from64(meta.nonceBase64));
 decipher.setAAD(from64(meta.aadBase64));
 decipher.setAuthTag(payload.subarray(payload.length-16));
 const plain=Buffer.concat([decipher.update(payload.subarray(0,-16)),decipher.final()]);
 if(hash(plain)!==meta.plaintextSha256)throw new Error('Signing backup SHA-256 mismatch');
 secureWrite(outputZip,plain);
 console.log('Signing backup decrypted and integrity-verified. SHA-256: '+hash(plain));
}else usage();
