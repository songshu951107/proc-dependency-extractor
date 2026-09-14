
const http = require("http");
function post(path, data) {
  return new Promise((resolve, reject) => {
    const req = http.request({hostname:"localhost",port:8080,path:"/api"+path,method:"POST",headers:{"Content-Type":"application/json"}}, res => {
      let body=""; res.on("data", c => body+=c); res.on("end", () => resolve(JSON.parse(body)));
    });
    req.on("error", reject);
    req.write(JSON.stringify(data));
    req.end();
  });
}
function get(path) {
  return new Promise((resolve, reject) => {
    http.get("http://localhost:8080/api"+path, res => { let body=""; res.on("data",c=>body+=c); res.on("end",()=>resolve(JSON.parse(body))); }).on("error", reject);
  });
}
async function run() {
  try {
    console.log("=== 1. schemas ===");
    const s = await get("/impact/schemas"); console.log(JSON.stringify(s).slice(0,300));
    console.log("
=== 2. tables for FDM ===");
    const t = await get("/impact/tables?schema=FDM"); console.log(JSON.stringify(t).slice(0,500));
    console.log("
=== 3. analyze ===");
    const a = await post("/impact/analyze", {schema:"FDM",tableShortName:"f_ac_ctds_ldr_acct",tableFullName:"FDM.f_ac_ctds_ldr_acct",fieldName:"acct_no",direction:"DOWNSTREAM",maxDepth:20});
    console.log("keys:", Object.keys(a.data||a));
    const d = a.data || a;
    console.log("nodes count:", d.nodes?.length || d.graphInitialNodes?.length);
    console.log("edges count:", d.edges?.length || d.graphInitialEdges?.length);
    const nodes = d.nodes || d.graphInitialNodes || [];
    const edges = d.edges || d.graphInitialEdges || [];
    console.log("
=== TRANSFORM nodes (expression starts with CASE) ===");
    const targets = nodes.filter(n => n.nodeLevel==="TRANSFORM" && (n.expression||"").startsWith("CASE WHEN T12.ACCT_NO IS NOT N"));
    targets.forEach((n,i) => {
      console.log("
Node "+i+": id="+n.id+", expr="+n.expression?.slice(0,80));
      const ins = edges.filter(e => e.target===n.id && e.edgeType==="INPUT_TO");
      console.log("  Inputs (count="+ins.length+"):");
      ins.forEach(inp => console.log("    colKey="+inp.source+", role="+(inp.role||"value")+", alias="+(inp.sourceAlias||"null")));
    });
    console.log("
Total TRANSFORM nodes with CASE WHEN T12...: "+targets.length);
    if (targets.length===0) {
      // show some sample nodes
      console.log("Sample TRANSFORM nodes:");
      const samples = nodes.filter(n=>n.nodeLevel==="TRANSFORM").slice(0,5);
      samples.forEach(n=>console.log("  "+n.id+": "+(n.expression||"").slice(0,60)));
      console.log("
Sample edges:");
      edges.slice(0,10).forEach(e=>console.log("  "+e.source+" --["+(e.edgeType||"")+","+(e.role||"")+","+(e.sourceAlias||"")+"]--> "+e.target));
    }
    require("fs").writeFileSync("api-result.json", JSON.stringify(a, null, 2));
    console.log("
Full result saved to api-result.json");
  } catch(e) { console.error(e); process.exit(1); }
}
run();
