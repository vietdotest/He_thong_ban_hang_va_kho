const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),pkg=path.dirname(require.resolve('@fortawesome/fontawesome-free/package.json'));
const names=fs.readFileSync(path.join(__dirname,'icons.txt'),'utf8').trim().split(/\s+/);
if(new Set(names).size!==names.length)throw Error('Tên icon trùng nhau.');
const symbols=names.map(name=>{
  if(!/^[a-z0-9-]+$/.test(name))throw Error('Tên icon không hợp lệ.');
  const svg=fs.readFileSync(path.join(pkg,'svgs/solid',name+'.svg'),'utf8');
  const viewBox=svg.match(/viewBox="([\d.\s]+)"/)?.[1];
  if(!viewBox)throw Error('Icon thiếu viewBox: '+name);
  const paths=[...svg.matchAll(/<path\b[^>]*\bd="([^"]+)"[^>]*\/?\s*>/g)].map(m=>'<path d="'+m[1]+'"/>').join('');
  if(!paths)throw Error('Icon thiếu đường nét: '+name);
  return `<symbol id="${name}" viewBox="${viewBox}">${paths}</symbol>`;
});
const target=path.join(root,'src/main/webapp/assets/icons');fs.mkdirSync(target,{recursive:true});
const sprite='<svg xmlns="http://www.w3.org/2000/svg">\n'+symbols.join('\n')+'\n</svg>\n';
if(Buffer.byteLength(sprite)>40*1024)throw Error('Sprite vượt 40 KB.');
fs.writeFileSync(path.join(target,'fa-sprite.svg'),sprite);
fs.writeFileSync(path.join(target,'LICENSE.txt'),'Font Awesome Free 7.3.1 — Fonticons, Inc.\nhttps://fontawesome.com/\nSVG Solid icons: CC BY 4.0.\n\n'+fs.readFileSync(path.join(pkg,'LICENSE.txt'),'utf8'));
console.log(`${names.length} icon, ${Buffer.byteLength(sprite)} byte.`);
