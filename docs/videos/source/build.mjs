// Builds docs/videos/warden-plus-intro.mp4 and docs/images/warden-plus-intro-preview.gif
// from real emulator captures. See README.md in this folder.
//
//   node build.mjs --raw <dir with the raw screen recordings> [--tools <dir with node_modules>]
//                  [--work <scratch dir>] [--browser msedge|chrome]
//
// Needs: Node 18+, playwright-core, ffmpeg-static (npm), and a locally installed Edge or Chrome.
import { createRequire } from 'node:module';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const docs = path.resolve(here, '..', '..');
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, all) =>
  (v.startsWith('--') ? [...a, [v.slice(2), all[i + 1]]] : a), []));
const rawDir = path.resolve(args.raw ?? path.join(here, 'raw'));
const toolsDir = path.resolve(args.tools ?? here);
const work = path.resolve(args.work ?? path.join(os.tmpdir(), 'warden-plus-intro'));
const browserChannel = args.browser ?? 'msedge';
const require = createRequire(path.join(toolsDir, 'package.json'));
const ffmpeg = process.env.FFMPEG ?? require('ffmpeg-static');
const { chromium } = require('playwright-core');

const FPS = 30;
const OUT_MP4 = path.join(docs, 'videos', 'warden-plus-intro.mp4');
const OUT_GIF = path.join(docs, 'images', 'warden-plus-intro-preview.gif');

// Phone recordings (adb shell screenrecord, 904x2316). ss = start in the recording,
// dur = length taken from it, speed > 1 plays it faster.
const SEGMENTS = [
  { id: 'home-on', src: 'home-on.mp4', ss: 0.5, dur: 6.0, speed: 1.0 },
  { id: 'web-add', src: 'web-add.mp4', ss: 1.0, dur: 17.5, speed: 3.0 },
  { id: 'pause', src: 'pause.mp4', ss: 9.6, dur: 17.5, speed: 3.2 },
  { id: 'hosts', src: 'hosts.mp4', ss: 0.0, dur: 4.6, speed: 1.0 },
  { id: 'schedule', src: 'schedule.mp4', ss: 33.5, dur: 21.0, speed: 3.4 },
  { id: 'block-until', src: 'block-until.mp4', ss: 0.5, dur: 25.0, speed: 4.0 },
  { id: 'protection', src: 'protection.mp4', ss: 13.0, dur: 21.0, speed: 3.8 },
  { id: 'usage', src: 'usage.mp4', ss: 2.0, dur: 9.5, speed: 1.6 },
  { id: 'wizard-done', src: 'wizard-done.mp4', ss: 0.0, dur: 6.0, speed: 1.0 },
  { id: 'guide', src: 'guide.mp4', ss: 1.0, dur: 10.0, speed: 1.6 },
];
// Still screenshots taken from docs/images.
const STILLS = ['hosts-lists.jpg', 'allow-only.jpg', 'categories.jpg', 'protection.jpg', 'usage-dark.jpg'];

function run(cmd, argv) {
  const r = spawnSync(cmd, argv, { stdio: ['ignore', 'inherit', 'inherit'] });
  if (r.status !== 0) throw new Error(`${path.basename(cmd)} failed (${r.status})`);
}

fs.rmSync(work, { recursive: true, force: true });
fs.mkdirSync(path.join(work, 'img'), { recursive: true });

// 1. Phone recordings -> constant 30 fps JPEG frame sequences (452 px wide).
const frames = {};
for (const s of SEGMENTS) {
  const dir = path.join(work, 'frames', s.id);
  fs.mkdirSync(dir, { recursive: true });
  run(ffmpeg, ['-v', 'error', '-ss', String(s.ss), '-t', String(s.dur), '-i', path.join(rawDir, s.src),
    '-vf', `setpts=(PTS-STARTPTS)/${s.speed},fps=${FPS},scale=452:-2:flags=lanczos`,
    '-q:v', '3', path.join(dir, '%04d.jpg')]);
  frames[s.id] = fs.readdirSync(dir).length;
  console.log(`segment ${s.id}: ${frames[s.id]} frames`);
}
for (const f of STILLS) fs.copyFileSync(path.join(docs, 'images', f), path.join(work, 'img', f));
fs.writeFileSync(path.join(work, 'manifest.js'), `window.MANIFEST = ${JSON.stringify({ fps: FPS, frames })};\n`);
fs.copyFileSync(path.join(here, 'intro.html'), path.join(work, 'intro.html'));

// 2. Render every frame in a headless browser and pipe it into ffmpeg (H.264, yuv420p, faststart).
const browser = await chromium.launch({ channel: browserChannel, headless: true });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
await page.goto(pathToFileURL(path.join(work, 'intro.html')).href);
await page.waitForFunction(() => window.ready === true);
const duration = await page.evaluate(() => window.DURATION);
const total = Math.round(duration * FPS);
fs.mkdirSync(path.dirname(OUT_MP4), { recursive: true });
const enc = spawn(ffmpeg, ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '24', '-tune', 'stillimage', '-pix_fmt', 'yuv420p',
  '-r', String(FPS), '-movflags', '+faststart', OUT_MP4], { stdio: ['pipe', 'inherit', 'inherit'] });
const done = new Promise((res, rej) => enc.on('close', c => (c === 0 ? res() : rej(new Error('ffmpeg ' + c)))));
for (let f = 0; f < total; f++) {
  await page.evaluate(t => window.seek(t), f / FPS);
  const jpg = await page.screenshot({ type: 'jpeg', quality: 94 });
  if (!enc.stdin.write(jpg)) await new Promise(r => enc.stdin.once('drain', r));
  if (f % 150 === 0) console.log(`frame ${f}/${total}`);
}
enc.stdin.end();
await done;
await browser.close();
console.log('wrote', OUT_MP4);

// 3. Preview GIF: a ~9 s excerpt, 640 px wide, 12 fps, own palette.
const pal = path.join(work, 'palette.png');
const gifFrom = args['gif-from'] ?? '0', gifDur = args['gif-dur'] ?? '10';
const gifVf = 'fps=12,scale=640:-1:flags=lanczos';
run(ffmpeg, ['-y', '-v', 'error', '-ss', gifFrom, '-t', gifDur, '-i', OUT_MP4, '-vf', `${gifVf},palettegen=max_colors=128:stats_mode=diff`, pal]);
run(ffmpeg, ['-y', '-v', 'error', '-ss', gifFrom, '-t', gifDur, '-i', OUT_MP4, '-i', pal,
  '-lavfi', `${gifVf}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle`, OUT_GIF]);
console.log('wrote', OUT_GIF);
