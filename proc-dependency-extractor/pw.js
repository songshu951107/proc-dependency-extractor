const { chromium } = require("playwright");
const fs = require("fs");
async function main() {
  const browser = await chromium.launch({ headless: false });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const p = await ctx.newPage();
  try {
    console.log("S1: Navigate");
    await p.goto("http://localhost:5173/impact", { waitUntil: "networkidle", timeout: 60000 });
    await p.waitForTimeout(3000);
    await p.screenshot({ path: "s1.png" });
    console.log("URL:", p.url());
    console.log("S2: Fill filters");
    await p.waitForTimeout(2000);
