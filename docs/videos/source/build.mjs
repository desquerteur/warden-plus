// Builds docs/videos/warden-plus-intro.mp4 (1920x1080, 60 fps) and
// docs/images/warden-plus-intro-preview.gif from real emulator captures. See README.md here.
//
//   node build.mjs --raw <dir with the raw screen recordings> [--tools <dir with node_modules>]
//                  [--work <scratch dir>] [--browser msedge|chrome] [--step prepare|render|encode|all]
//                  [--part-seconds 5] [--crf 27]
//
// The render step works in parts (default 5 s each) and skips parts that are already done,
// so a stopped run can simply be started again. Progress is printed one line per second.
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
const step = args.step ?? 'all';
const browserChannel = args.browser ?? 'msedge';
const partSeconds = Number(args['part-seconds'] ?? 5);
const crf = String(args.crf ?? 27);
const require = createRequire(path.join(toolsDir, 'package.json'));
const ffmpeg = process.env.FFMPEG ?? require('ffmpeg-static');

const FPS = 60;
const OUT_MP4 = path.join(docs, 'videos', 'warden-plus-intro.mp4');
const OUT_GIF = path.join(docs, 'images', 'warden-plus-intro-preview.gif');

// Phone recordings (adb shell screenrecord, 904x2316). ss = start in the recording,
// dur = length taken from it, speed > 1 plays it faster. taps: where and when (seconds in the
// recording, device pixels) a finger touched the screen; `to` + `d` make it a swipe.
const SEGMENTS = [
  { id: 'home-on', src: 'home-on.mp4', ss: 0.5, dur: 6.0, speed: 1.0,
    taps: [{ t: 1.72, x: 760, y: 400 }] },
  { id: 'web-add', src: 'web-add.mp4', ss: 1.0, dur: 18.0, speed: 3.0,
    taps: [{ t: 1.5, x: 345, y: 421 }, { t: 15.8, x: 776, y: 379 }] },
  { id: 'pause', src: 'pause.mp4', ss: 9.6, dur: 17.5, speed: 3.2, taps: [] },
  { id: 'hosts', src: 'hosts.mp4', ss: 0.0, dur: 4.6, speed: 1.0,
    taps: [{ t: 1.85, x: 199, y: 1022 }] },
  { id: 'schedule', src: 'schedule.mp4', ss: 33.5, dur: 21.0, speed: 3.2,
    taps: [{ t: 35.3, x: 451, y: 1486 }, { t: 41.7, x: 543, y: 1473 }, { t: 43.6, x: 774, y: 1778 }, { t: 52.75, x: 722, y: 1775 }] },
  { id: 'block-until', src: 'block-until.mp4', ss: 0.5, dur: 25.0, speed: 3.8,
    taps: [{ t: 1.6, x: 283, y: 1790 }, { t: 14.95, x: 526, y: 1161 }, { t: 22.85, x: 452, y: 1497 }] },
  { id: 'protection', src: 'protection.mp4', ss: 12.0, dur: 23.5, speed: 3.8,
    taps: [{ t: 12.2, x: 280, y: 760 }, { t: 21.55, x: 205, y: 906 }, { t: 30.35, x: 231, y: 958 },
      { t: 31.85, x: 399, y: 958 }, { t: 32.85, x: 567, y: 958 }, { t: 34.6, x: 231, y: 1126 }] },
  { id: 'usage', src: 'usage.mp4', ss: 2.0, dur: 9.5, speed: 1.6,
    taps: [{ t: 2.55, x: 625, y: 2190 }, { t: 5.85, x: 450, y: 1700, to: [450, 900], d: 0.9 },
      { t: 9.95, x: 450, y: 1700, to: [450, 900], d: 0.9 }] },
  { id: 'wizard-done', src: 'wizard-done.mp4', ss: 0.0, dur: 6.0, speed: 1.0,
    taps: [{ t: 2.3, x: 770, y: 2169 }] },
  // The guide draws its own finger, so no extra tap circles here.
  { id: 'guide', src: 'guide.mp4', ss: 1.0, dur: 10.0, speed: 1.6, taps: [] },
];
// Still screenshots taken from docs/images.
const STILLS = ['hosts-lists.jpg', 'allow-only.jpg', 'categories.jpg', 'protection.jpg', 'usage-dark.jpg'];

function run(cmd, argv) {
  const r = spawnSync(cmd, argv, { stdio: ['ignore', 'inherit', 'inherit'] });
  if (r.status !== 0) throw new Error(`${path.basename(cmd)} failed (${r.status})`);
}
const log = (...m) => console.log(new Date().toISOString().slice(11, 19), ...m);

// 1. Phone recordings -> constant 60 fps JPEG frame sequences (452 px wide).
if (step === 'all' || step === 'prepare') {
  fs.rmSync(work, { recursive: true, force: true });
  fs.mkdirSync(path.join(work, 'img'), { recursive: true });
  const segments = {};
  for (const s of SEGMENTS) {
    const dir = path.join(work, 'frames', s.id);
    fs.mkdirSync(dir, { recursive: true });
    run(ffmpeg, ['-v', 'error', '-ss', String(s.ss), '-t', String(s.dur), '-i', path.join(rawDir, s.src),
      '-vf', `setpts=(PTS-STARTPTS)/${s.speed},fps=${FPS},scale=452:-2:flags=lanczos`,
      '-q:v', '3', path.join(dir, '%05d.jpg')]);
    segments[s.id] = { frames: fs.readdirSync(dir).length, ss: s.ss, speed: s.speed, taps: s.taps };
    log(`segment ${s.id}: ${segments[s.id].frames} frames`);
  }
  for (const f of STILLS) fs.copyFileSync(path.join(docs, 'images', f), path.join(work, 'img', f));
  fs.writeFileSync(path.join(work, 'manifest.js'), `window.MANIFEST = ${JSON.stringify({ fps: FPS, segments })};\n`);
}
fs.copyFileSync(path.join(here, 'intro.html'), path.join(work, 'intro.html'));

// 2. Render every frame in a headless browser, part by part, piping JPEGs into ffmpeg.
const partsDir = path.join(work, 'parts');
fs.mkdirSync(partsDir, { recursive: true });
if (step === 'all' || step === 'render') {
  const browser = await chromium().launch({ channel: browserChannel, headless: true });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => log('page error:', e.message));
  await page.goto(pathToFileURL(path.join(work, 'intro.html')).href);
  await page.waitForFunction(() => window.ready === true);
  const total = Math.round(await page.evaluate(() => window.DURATION) * FPS);
  const per = Math.round(partSeconds * FPS);
  const parts = Math.ceil(total / per);
  log(`render: ${total} frames in ${parts} parts`);
  for (let p = 0; p < parts; p++) {
    const name = path.join(partsDir, `part-${String(p).padStart(2, '0')}.mp4`);
    if (fs.existsSync(name + '.done')) { log(`part ${p} done already`); continue; }
    const enc = spawn(ffmpeg, ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
      '-c:v', 'libx264', '-preset', 'slow', '-crf', crf, '-pix_fmt', 'yuv420p', '-g', '120', '-bf', '3',
      '-x264-params', 'aq-mode=3:deblock=1,1', '-r', String(FPS), name], { stdio: ['pipe', 'inherit', 'inherit'] });
    const done = new Promise((res, rej) => enc.on('close', c => (c === 0 ? res() : rej(new Error('ffmpeg ' + c)))));
    const from = p * per, to = Math.min(total, from + per);
    for (let f = from; f < to; f++) {
      await page.evaluate(t => window.seek(t), f / FPS);
      const jpg = await page.screenshot({ type: 'jpeg', quality: 93 });
      if (!enc.stdin.write(jpg)) await new Promise(r => enc.stdin.once('drain', r));
      if (f % FPS === 0) log(`frame ${f}/${total} (part ${p + 1}/${parts})`);
    }
    enc.stdin.end();
    await done;
    fs.writeFileSync(name + '.done', '');
    log(`part ${p} written`);
  }
  await browser.close();
}

// 3. Join the parts (no re-encode), add faststart, then the preview GIF.
if (step === 'all' || step === 'encode') {
  const list = fs.readdirSync(partsDir).filter(f => /^part-\d+\.mp4$/.test(f)).sort();
  fs.writeFileSync(path.join(work, 'parts.txt'), list.map(f => `file '${path.join(partsDir, f).replace(/\\/g, '/')}'`).join('\n'));
  fs.mkdirSync(path.dirname(OUT_MP4), { recursive: true });
  run(ffmpeg, ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', path.join(work, 'parts.txt'), '-c', 'copy', '-movflags', '+faststart', OUT_MP4]);
  log('wrote', OUT_MP4);
  const pal = path.join(work, 'palette.png');
  const gifFrom = args['gif-from'] ?? '0', gifDur = args['gif-dur'] ?? '9', gifFps = args['gif-fps'] ?? '20';
  const gifVf = `fps=${gifFps},scale=640:-1:flags=lanczos`;
  run(ffmpeg, ['-y', '-v', 'error', '-ss', gifFrom, '-t', gifDur, '-i', OUT_MP4, '-vf', `${gifVf},palettegen=max_colors=96:stats_mode=full`, pal]);
  run(ffmpeg, ['-y', '-v', 'error', '-ss', gifFrom, '-t', gifDur, '-i', OUT_MP4, '-i', pal,
    '-lavfi', `${gifVf}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle`, OUT_GIF]);
  log('wrote', OUT_GIF);
}

function chromium() { return require('playwright-core').chromium; }
