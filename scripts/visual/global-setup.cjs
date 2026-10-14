const {chromium}=require('@playwright/test'),fs=require('node:fs'),path=require('node:path');
module.exports=async(config)=>{
  const root=path.resolve(__dirname,'../..'),baseURL=config.projects[0].use.baseURL;
  const credentials=JSON.parse(fs.readFileSync(path.join(root,'.tools/ui-preview-accounts.json'),'utf8').replace(/^\uFEFF/,''));
  const browser=await chromium.launch();fs.mkdirSync(path.join(__dirname,'.auth'),{recursive:true});
  try{for(const [actor,username]of Object.entries(credentials.accounts)){
    const context=await browser.newContext({baseURL});const page=await context.newPage();await page.goto('/login');await page.locator('[name=identity]').fill(username);await page.locator('[name=password]').fill(credentials.password);
    await Promise.all([page.waitForURL(url=>['/dashboard','/portal'].includes(url.pathname)),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);
    await context.storageState({path:path.join(__dirname,'.auth',actor+'.json')});await context.close();
  }}finally{await browser.close();}
};
