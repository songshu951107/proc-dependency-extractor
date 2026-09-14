const http = require('http');
const fs = require('fs');
const data = JSON.stringify({schema:'FDM',tableShortName:'f_ac_ctds_ldr_acct',tableFullName:'FDM.f_ac_ctds_ldr_acct',fieldName:'acct_no',direction:'DOWNSTREAM',maxDepth:20});
const req = http.request({hostname:'localhost',port:8080,path:'/api/impact/analyze',method:'POST',headers:{'Content-Type':'application/json','Content-Length':data.length}},res=>{
  let b='';res.on('data',c=>b+=c);res.on('end',()=>{
    const o=JSON.parse(b); const d=o.data||o;
    const ns=d.nodes||d.graphInitialNodes||[]; const es=d.edges||d.graphInitialEdges||[];
    console.log('Nodes:'+ns.length+' Edges:'+es.length);
    // Find node: expression contains T12.ACCT_NO IS NOT NULL (may have newlines/whitespace between CASE and WHEN)
    function norm(s){return (s||'').replace(/[\s\n\r\t]+/g,' ').trim();}
    const ts=ns.filter(n=>n.nodeLevel==='TRANSFORM' && norm(n.expression).startsWith('CASE WHEN T12.ACCT_NO IS NOT N'));
    console.log('TARGET_COUNT:'+ts.length);
    ts.forEach((n,i)=>{
      console.log('=== NODE '+i+' ===');
      console.log('id:'+n.id);
      console.log('raw expr:'+JSON.stringify((n.expression||'').slice(0,150)));
      console.log('normalized:'+norm(n.expression).slice(0,120));
      const ins=es.filter(e=>e.target===n.id && e.edgeType==='INPUT_TO');
      console.log('INPUT_COUNT:'+ins.length);
      console.log('--- INPUT FIELDS ---');
      ins.forEach((f,j)=>console.log(' '+(j+1)+'. colKey='+f.source+' | role='+(f.role||'value')+' | alias='+(f.sourceAlias||'NULL')));
      // Verification
      const exp=[{colKey:'acct_no',role:'condition',alias:'T'},{colKey:'acct_no',role:'condition',alias:'T12'},{colKey:'ldr_frozen_principal',role:'value',alias:'T'},{colKey:'acct_bal',role:'value',alias:'T'}];
      console.log('--- VERIFICATION ---');
      if(ins.length===4){
        let pass=true;
        for(let k=0;k<4;k++){
          const a=ins[k],e=exp[k];
          const ok=(a.source===e.colKey)&&((a.role||'value')===e.role)&&((a.sourceAlias||'NULL')===e.alias);
          if(!ok){pass=false;console.log('FAIL item '+(k+1)+': got colKey='+a.source+'/role='+(a.role||'value')+'/alias='+(a.sourceAlias||'NULL'));}
        }
        console.log(pass?'VERIFY: PASS':'VERIFY: FAIL content');
      }else{console.log('VERIFY: FAIL count expected=4 got='+ins.length);}
    });
    // Save full result for reference
    fs.writeFileSync('full-analysis.json',JSON.stringify({nodes:ns,edges:es},null,2));
    console.log('Saved to full-analysis.json');
  });
});
req.write(data); req.end(); req.on('error',e=>console.error('ERR:'+e.message));