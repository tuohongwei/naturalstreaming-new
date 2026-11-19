(function(root){
 'use strict';
 function safeCount(value){return Array.isArray(value)?value.length:0;}
 function mediaKey(manifest){
  if(!Array.isArray(manifest?.files)||!manifest.files.every(f=>f&&typeof f.path==='string'&&typeof f.sha256==='string'))return null;
  return JSON.stringify(manifest.files.map(f=>[f.path,f.sha256]).sort((a,b)=>a[0].localeCompare(b[0])||a[1].localeCompare(b[1])));
 }
 function sameMedia(a,b){const x=mediaKey(a),y=mediaKey(b);return x!==null&&y!==null&&x===y;}
 function importLicense(license,oldManifest,newManifest){return {...(license||{source:'',license:''}),reviewed:license?.reviewed===true&&sameMedia(oldManifest,newManifest)};}
 function structured(d){return Array.isArray(d?.scene?.tracks)&&d.scene.tracks.length<=32&&d.scene.tracks.every(t=>t&&Array.isArray(t.clips)&&t.clips.length===2)&&Array.isArray(d?.scene?.presets)&&d.scene.presets.length<=64&&d.scene.presets.every(p=>p&&p.gains&&typeof p.gains==='object')&&Array.isArray(d?.manifest?.files)&&d.manifest.files.length<=129&&d.manifest.files.every(f=>f&&typeof f.path==='string'&&typeof f.sha256==='string');}
 const api={safeCount,mediaKey,sameMedia,importLicense,structured};root.NSConsolePolicy=api;
 if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(globalThis);
