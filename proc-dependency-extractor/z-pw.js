process.chdir("D:/HB/work_zuoye/proc-dependency-extractor");
const {chromium} = require("playwright");
const fs = require("fs");
const TID = "5b72f2c50ce1841c68f8388b3106bb86";
(async () => {
  const b = await chromium.launch({headless:false, channel:"msedge"});
  const c = await b.newContext({viewport:{width:1920, height:1080}});
  const p = await c.newPage();
  try {
    await p.goto("http://localhost:5173/impact", {waitUntil:"networkidle", timeout:60000});
    await p.waitForTimeout(3500);
    async function pick(lbl, val, filterable) {
      const items = await p.$$(".el-form-item");
      for (const fi of items) {
        const lb = await fi.$("label");
        const t = lb ? (await lb.textContent()).trim() : "";
        if (t === lbl) {
          const w = await fi.$(".el-select__wrapper, .el-select .el-input__wrapper");
          if (w) {
            await w.click(); await p.waitForTimeout(900);
            const opts = await p.$$(".el-option"); let got = false;
            for (const o of opts) {
              const tx = (await o.textContent()).trim();
              if (tx===val || tx.endsWith("."+val) || (tx.length<val.length+40 && tx.indexOf(val)>=0)) { await o.click(); got=true; break; }
            }
            if (!got && filterable) {
              const sel = ".el-select__filter input, .el-select-dropdown input, .el-popper input, .el-select .el-input__inner";
              const di = await p.$(sel);
              if (di) { try { await di.fill(val); await p.waitForTimeout(900); const o2 = await p.$(".el-option"); if (o2) { await o2.click(); got=true; } } catch(e) {} }
            }
            if (!got) { try { await p.keyboard.press("Escape"); await p.waitForTimeout(200); } catch(_) {} }
            await p.waitForTimeout(1800); return true;
          }
        }
      }
      return false;
    }
    await pick("Schema", "FDM", false); await p.waitForTimeout(2500);
    await pick("表名", "f_ac_ctds_ldr_acct", true); await p.waitForTimeout(2500);
    await pick("字段名", "acct_no", true); await p.waitForTimeout(1800);
    await pick("方向", "下游", false); await p.waitForTimeout(1000);
    console.log("Setting 最大深度 to 全部..."); await pick("最大深度", "全部", false); await p.waitForTimeout(1500);
    const bs = await p.$$(".el-button,button");
    for (const x of bs) { if ((await x.textContent()).trim() === "分析") { console.log("Clicked 分析"); await x.click(); break; } }
    for (let i=0; i<60; i++) {
      const l = await p.evaluate(() => document.querySelectorAll(".el-loading-mask,.is-loading").length);
      if (l === 0) { console.log("Loading done i="+i); break; }
      await p.waitForTimeout(2500);
    }
    await p.waitForTimeout(10000);
    console.log("Post-analyze: try pick 最大深度 again if exists...");
    await pick("最大深度", "全部", false); await p.waitForTimeout(2000);
    console.log(""); console.log("========== DEEP DUMP ==========");
    const dump = await p.evaluate(() => {
      const rep = {};
      const svgs = document.querySelectorAll("svg");
      rep.svgCount = svgs.length;
      rep.svgDetails = [];
      svgs.forEach((s, idx) => {
        const det = {idx, tags:{}, cls:(s.className&&s.className.baseVal?s.className.baseVal:(s.className||"")), w:s.getAttribute("width"), h:s.getAttribute("height"), vb:s.getAttribute("viewBox"), sampleHTML:s.outerHTML.slice(0,2500)};
        s.querySelectorAll("*").forEach(c => { det.tags[c.tagName] = (det.tags[c.tagName]||0)+1; });
        rep.svgDetails.push(det);
      });
      // 找所有含foreignObject的svg内部HTML片段
      rep.foreignObjectHTML = [];
      document.querySelectorAll("foreignObject").forEach(f => { rep.foreignObjectHTML.push(f.outerHTML.slice(0,2000)); });
      // 统计所有div中较长的文本，看是否有节点文字
      rep.divLongTexts = [];
      document.querySelectorAll("div,span,p").forEach(d => {
        if (d.children && d.children.length === 0) {
          const t = (d.textContent||"").replace(/\s+/g," ").trim();
          if (t.length >= 6) rep.divLongTexts.push({tag:d.tagName,cls:(d.className||"").toString().slice(0,80),txt:t.slice(0,300)});
        }
      });
      rep.divLongTexts = rep.divLongTexts.slice(0, 400);
      // 搜索CASE/T12/TRANSFORM/SOURCE/JOIN/FILTER等图节点关键词
      const KW = ["CASE", "T12", "TRANSFORM", "SOURCE", "JOIN", "FILTER", "AGGREGATE", "UNION", "MERGE", "f_ac_ctds_ldr_acct", "ldr_frozen_principal", "acct_bal", "FDM.", "DWS.", "ADS.", "DWD.", "DIM.", "ODS."];
      rep.kwHits = [];
      const w = document.createTreeWalker(document.body, 4); let tn;
      while (tn = w.nextNode()) {
        const s = (tn.nodeValue||"").replace(/\s+/g, " ").trim();
        if (!s) continue;
        for (const kw of KW) {
          if (s.indexOf(kw)>=0) { rep.kwHits.push({kw, txt:s.slice(0,500)}); break; }
        }
        if (rep.kwHits.length >= 200) break;
      }
      // dump所有表单标签，确认是否有深度控件
      rep.formLabels = [];
      document.querySelectorAll(".el-form-item").forEach(fi => {
        const lb = fi.querySelector("label");
        const t = lb ? lb.textContent.trim() : "";
        const valWrap = fi.querySelector(".el-select__selected-item, .el-input__inner, input, .el-radio-button__inner, .el-checkbox__label");
        const v = valWrap ? (valWrap.value || valWrap.textContent || "").toString().replace(/\s+/g," ").trim().slice(0,120) : "";
        rep.formLabels.push({label:t, val:v});
      });
      return rep;
    });
    console.log("SVG count=" + dump.svgCount);
    dump.svgDetails.forEach(s => console.log("  SVG "+s.idx+": w="+s.w+" h="+s.h+" vb="+s.vb+" tags="+JSON.stringify(s.tags)+" cls="+s.cls));
    console.log(""); console.log("formLabels (count="+dump.formLabels.length+"):");
    dump.formLabels.forEach(f => console.log("  "+f.label+" = "+f.val));
    console.log(""); console.log("foreignObject count="+dump.foreignObjectHTML.length);
    dump.foreignObjectHTML.forEach((h,i) => console.log("  FO"+i+": "+h));
    console.log(""); console.log("Keyword hits (count="+dump.kwHits.length+"):");
    dump.kwHits.forEach(k => console.log("  kw="+k.kw+" :: "+k.txt));
    console.log(""); console.log("Leaf div/span/p long texts (count="+dump.divLongTexts.length+"): show first 100");
    for (let i=0; i<Math.min(100, dump.divLongTexts.length); i++) { console.log("  D"+i+" <"+dump.divLongTexts[i].tag+"> "+dump.divLongTexts[i].cls+" :: "+dump.divLongTexts[i].txt); }
    fs.writeFileSync("z-deep-dump.json", JSON.stringify(dump, null, 2));
    await p.screenshot({path:"z-deep.png", fullPage:true});
    console.log("Saved z-deep-dump.json, z-deep.png");
    await b.close(); console.log("DONE");
  } catch(e) {
    console.error("FATAL", e.message); console.error(e.stack);
    try { await p.screenshot({ path:"zERR.png" }); } catch(_) {}
    try { fs.writeFileSync("zERR.txt", e.stack || String(e)); } catch(_) {}
    await b.close(); process.exit(1);
  }
})();