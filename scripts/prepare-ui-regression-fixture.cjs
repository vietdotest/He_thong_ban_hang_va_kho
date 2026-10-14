// Fixture nghiệp vụ chỉ được gọi sau guard database tạm trong run-ui-regression.cjs.
const fs=require('node:fs'),path=require('node:path');
module.exports=function prepare(query,root){
 const businessFixture=path.join(root,'.tools/ui-s3-evidence/business-ux-fixture.json');
 if(fs.existsSync(businessFixture)){
  const fixture=JSON.parse(fs.readFileSync(businessFixture,'utf8'));
  if(!/^qa-ux-[a-z0-9]+-actor$/.test(fixture.username))throw Error('Fixture có tên ngoài phạm vi thử.');
  if(query(`SELECT COUNT(*) FROM users WHERE username='${fixture.username}';`)==='0')fs.renameSync(businessFixture,businessFixture+'.stale-'+Date.now());
 }
 const statements=[];
 for(const file of fs.readdirSync(path.join(root,'src/main/resources/db/migration')).filter(f=>f.endsWith('.sql')).sort()){
  const text=fs.readFileSync(path.join(root,'src/main/resources/db/migration',file),'utf8');
  for(const match of text.matchAll(/INSERT INTO role_permissions\(role_id,permission_id\)[\s\S]*?;/g)){
   let sql=match[0].replace('INSERT INTO role_permissions','INSERT IGNORE INTO ui_expected_grants');
   // Vai trò DEALER chỉ được tạo ở V027, sau seed quyền chung V005.
   if(file.startsWith('V005'))sql=sql.replace('FROM roles r',"FROM (SELECT * FROM roles WHERE code<>'DEALER') r");
   statements.push(sql);
  }
 }
 if(statements.length!==13)throw Error('Seed quyền đã đổi; đối chiếu fixture trước khi chạy.');
 query('CREATE TEMPORARY TABLE ui_expected_grants(role_id BIGINT UNSIGNED,permission_id BIGINT UNSIGNED,PRIMARY KEY(role_id,permission_id));\n'+statements.join('\n')+'\nSTART TRANSACTION; DELETE FROM role_permissions; INSERT INTO role_permissions(role_id,permission_id) SELECT role_id,permission_id FROM ui_expected_grants; COMMIT;');
 query("INSERT IGNORE INTO dealers(code,name,phone,group_id,territory_id,primary_staff_id) SELECT 'DL-QA-01','Đại lý Cà phê QA','0912345678',g.id,t.id,u.id FROM customer_groups g,territories t,users u WHERE t.code='S3-HN' AND u.username='s3-manager' ORDER BY g.id LIMIT 1;");
 const dealer=query("SELECT id FROM dealers WHERE code='DL-QA-01';");
 if(!/^\d+$/.test(dealer))throw Error('Thiếu đại lý fixture.');
 if(query('SELECT COUNT(*) FROM dealer_addresses WHERE dealer_id='+dealer)==='0')for(let n=0;n<21;n++)query(`INSERT INTO dealer_addresses(dealer_id,address,recipient,phone,is_default) VALUES(${dealer},'${n+1} Đường QA','Người nhận QA','0912345678',${n===0?1:0});`);
};
// Tồn và tín dụng synthetic đáp ứng các điều kiện MVP hiện hành khi kiểm UI đơn hàng.
module.exports.supply=function supply(query,warehouse,product,unit,actor){
 for(const value of [warehouse,product,unit,actor])if(!/^\d+$/.test(String(value)))throw Error('ID fixture không hợp lệ.');
 const code='UI-S3-STOCK-'+product;
 query(`INSERT IGNORE INTO suppliers(code,name,warehouse_id,status) VALUES('${code}','Nhà cung cấp fixture UI',${warehouse},'ACTIVE');`);
 const supplier=query(`SELECT id FROM suppliers WHERE code='${code}';`);
 const receipt=query(`INSERT INTO mvp_receipts(warehouse_id,supplier_id,document_ref,receipt_date,status,created_by,confirmed_by,confirmed_at) VALUES(${warehouse},${supplier},'${code}',CURDATE(),'CONFIRMED',${actor},${actor},NOW(6)); SELECT LAST_INSERT_ID();`);
 const line=query(`INSERT INTO mvp_receipt_lines(receipt_id,position,product_id,unit_id,quantity,factor,unit_version,base_quantity,lot_code,unit_name_snapshot,sku_snapshot,name_snapshot) SELECT ${receipt},0,${product},u.id,1000,1,u.version,1000,'${code}',u.name,p.sku,p.name FROM product_units u JOIN products p ON p.id=u.product_id WHERE u.id=${unit}; SELECT LAST_INSERT_ID();`);
 query(`INSERT INTO mvp_lots(warehouse_id,product_id,receipt_line_id,lot_code,received_date,on_hand) VALUES(${warehouse},${product},${line},'${code}',CURDATE(),1000);`);
};
