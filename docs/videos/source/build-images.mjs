// Converts raw PNG screenshots (adb exec-out screencap -p) into the README JPGs in docs/images
// and renders docs/images/hero.jpg from hero.html.
//
//   node build-images.mjs --caps <dir with PNG screenshots> [--tools <dir with node_modules>] [--browser msedge]
//
// Each docs/images/<name>.jpg is made from <caps>/<name>.png (max 900 px wide, JPEG quality ~85).
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const images = path.resolve(here, '..', '..', 'images');
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, all) =>
  (v.startsWith('--') ? [...a, [v.slice(2), all[i + 1]]] : a), []));
const require = createRequire(path.join(path.resolve(args.tools ?? here), 'package.json'));
const ffmpeg = process.env.FFMPEG ?? require('ffmpeg-static');

const NAMES = ['home', 'home-dark', 'blocklist', 'blocklist-dark', 'hosts-lists', 'schedules', 'block-until',
  'usage', 'usage-dark', 'settings', 'protection', 'pin', 'allow-only', 'categories', 'wizard', 'guide',
  'pause-screen', 'max-protection'];

fs.mkdirSync(images, { recursive: true });
if (args.caps) {
  for (const n of NAMES) {
    const src = path.join(path.resolve(args.caps), n + '.png');
    if (!fs.existsSync(src)) { console.log('skip (no capture):', n); continue; }
    // ffmpeg's -q:v 4 for MJPEG is roughly JPEG quality 85.
    const r = spawnSync(ffmpeg, ['-y', '-v', 'error', '-i', src, '-vf', "scale='min(900,iw)':-2:flags=lanczos",
      '-q:v', '4', path.join(images, n + '.jpg')], { stdio: 'inherit' });
    if (r.status !== 0) throw new Error('ffmpeg failed for ' + n);
    console.log('wrote', n + '.jpg');
  }
}

const { chromium } = require('playwright-core');
const browser = await chromium.launch({ channel: args.browser ?? 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 980 } });
await page.goto(pathToFileURL(path.join(here, 'hero.html')).href);
await page.evaluate(() => Promise.all([...document.images].map(i => i.decode())));
await page.screenshot({ path: path.join(images, 'hero.jpg'), type: 'jpeg', quality: 85 });
await browser.close();
console.log('wrote hero.jpg');
