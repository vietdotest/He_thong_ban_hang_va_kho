const fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '..');
function files(dir) {
  return fs.existsSync(dir) ? fs.readdirSync(dir, {withFileTypes:true}).filter(e=>!['node_modules','.tools','target','.git'].includes(e.name)).flatMap(e => e.isDirectory() ? files(path.join(dir,e.name)) : [path.join(dir,e.name)]) : [];
}
const views = files(path.join(root,'src/main/webapp/WEB-INF/views')).filter(f=>/\.jsp$/.test(f));
const js = files(path.join(root,'src/main/webapp/assets/js')).concat(files(path.join(root,'scripts')).filter(f=>/test[^/\\]*\.cjs$/.test(f)));
const hooks = new Set();
for (const file of js) {
  const source = fs.readFileSync(file,'utf8');
  for (const match of source.matchAll(/(?:querySelector(?:All)?|closest|matches|locator)\(\s*['"]([^'"\n]+)['"]/g)) {
    for (const cls of match[1].matchAll(/\.([a-zA-Z_][\w-]*)/g)) hooks.add(cls[1]);
  }
  for (const match of source.matchAll(/classList\.(?:add|remove|toggle|contains)\(\s*['"]([\w-]+)['"]/g)) hooks.add(match[1]);
}
const css = files(path.join(root,'src/main/webapp/assets/css')).filter(f=>/\.css$/.test(f));
const cssText = css.map(f=>fs.readFileSync(f,'utf8')).join('\n');
const styled = [...hooks].filter(c=>new RegExp('\\.'+c+'(?![\\w-])').test(cssText)).sort();
const inventory = views.sort().map(file=>{
  const source = fs.readFileSync(file,'utf8');
  return {view:path.relative(path.join(root,'src/main/webapp/WEB-INF/views'),file).replaceAll('\\','/').replace(/\.jsp$/,''),
    css:[...source.matchAll(/assets\/css\/([\w-]+\.css)/g)].map(m=>m[1]),
    scripts:[...source.matchAll(/assets\/js\/([\w-]+\.js)/g)].map(m=>m[1])};
});
const target = path.join(root,'src/test/resources/ui'); fs.mkdirSync(target,{recursive:true});
fs.writeFileSync(path.join(target,'protected-classes.txt'),[...hooks].sort().join('\n')+'\n');
fs.writeFileSync(path.join(target,'styled-classes.txt'),styled.join('\n')+'\n');
fs.writeFileSync(path.join(target,'source-inventory.json'),JSON.stringify(inventory,null,2)+'\n');
console.log(`${views.length} JSP; ${hooks.size} class JS/test; ${styled.length} class có CSS.`);
