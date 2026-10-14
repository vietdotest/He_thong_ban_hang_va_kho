const {defineConfig}=require('@playwright/test');
const baseURL=process.env.UI_PREVIEW_BASE_URL||'http://127.0.0.1:18087';
const url=new URL(baseURL);
if(!['127.0.0.1','localhost','host.docker.internal'].includes(url.hostname)||url.port!=='18087')throw Error('Bộ UI chỉ chạy trên Preview cô lập tại 18087.');
module.exports=defineConfig({
  testDir:__dirname,testMatch:'**/*.spec.cjs',timeout:45000,fullyParallel:false,workers:1,globalSetup:require.resolve('./global-setup.cjs'),
  forbidOnly:true,retries:0,reporter:[['list'],['json',{outputFile:'test-results/results.json'}]],
  use:{baseURL,locale:'vi-VN',timezoneId:'Asia/Ho_Chi_Minh',reducedMotion:'reduce',trace:'retain-on-failure'},
  expect:{toHaveScreenshot:{maxDiffPixelRatio:0.01,animations:'disabled',caret:'hide'}},
  projects:[360,768,1024,1100,1440].map(width=>({name:`chromium-${width}`,use:{browserName:'chromium',viewport:{width,height:1000},deviceScaleFactor:1}})),
  snapshotPathTemplate:'{testDir}/__screenshots__/{projectName}/{arg}{ext}'
});
