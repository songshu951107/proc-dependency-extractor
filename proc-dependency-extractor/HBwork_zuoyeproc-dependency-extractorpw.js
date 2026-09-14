
    async function sel(lbl, val) {
      console.log( Sel:, lbl, =>, val);
      const items = await p.(.el-form-item);
      for (const fi of items) {
        const lb = await fi.label;
        const t = lb ? (await lb.textContent()).trim() : ;
 if (t === lbl) {
 const w = await fi..el-select__wrapper, .el-input__inner;
 if (w) {
 await w.click(); await p.waitForTimeout(600);
 const opts = await p.(.el-option);
 let fnd = false;
 for (const o of opts) {
 const ot = (await o.textContent()).trim();
 if (ot === val || ot.includes(val)) { await o.click(); fnd = true; break; }
 }
 if (!fnd) {
 const inp = await fi..el-select input;
 if (inp) { await inp.fill(val); await p.waitForTimeout(600); const fo = await p..el-option; if (fo) await fo.click(); }
 }
 await p.waitForTimeout(1500); return true;
 }}
 } return false; }
 await sel(Schema, FDM); await p.waitForTimeout(2500);
 await sel(表名, f_ac_ctds_ldr_acct); await p.waitForTimeout(2500);
 await sel(字段名, acct_no); await p.waitForTimeout(1500);
 await sel(方向, 下游); await p.waitForTimeout(1000);
 await p.screenshot({ path: s2.png });
 console.log(S3: Click analyze);
 const btns = await p.(.el-button button);
 for (const b of btns) { if ((await b.textContent()).trim() === 分析) { await b.click(); break; } }
 console.log(Wait loading...);
 for (let i = 0; i < 30; i++) { const l = await p.evaluate(() => document.querySelectorAll(.el-loading-mask,.is-loading).length); if (!l) break; await p.waitForTimeout(2000); }
 await p.waitForTimeout(6000);
 await p.screenshot({ path: s3.png, fullPage: true });


    console.log( S4: Find node);
    const cr = await p.evaluate(s => {
      const r = { found: false, clicked: false, cand: [] };
      const w = document.createTreeWalker(document.body, 4);
      let tn; const md = [];
      while (tn = w.nextNode()) {
        const tx = tn.textContent.trim();
        if (tx && tx.length > 15 && tx.startsWith(s.slice(0, 20))) {
          let el = tn.parentElement;
          for (let i = 0; i < 8 && el; i++) {
            const cs = window.getComputedStyle(el).cursor;
            const cl = (el.className || ).toString();
 if (cs === pointer || cl.match(/node|transform|clickable/i)) { md.push(el); break; }
 el = el.parentElement;
 }}
 }
 r.cand = md.map(e => ({ cl: (e.className||).toString().slice(0,80), tx: e.textContent.slice(0,60) }));
      if (md.length) {
        r.found = true; const el = md[0];
        el.scrollIntoView({block:center});
        el.dispatchEvent(new MouseEvent(click,{bubbles:true,cancelable:true,view:window}));
        el.click(); r.clicked = true; r.text = el.textContent.slice(0,120);
      } return r;
    }, CASE WHEN T12.ACCT_NO IS NOT N);
    console.log(Click result:, JSON.stringify(cr, null, 2));
    await p.waitForTimeout(2500);
    await p.screenshot({ path: s4.png, fullPage: true });

