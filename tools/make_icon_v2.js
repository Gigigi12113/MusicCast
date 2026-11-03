#!/usr/bin/env node
/**
 * MusicCast 图标生成器 v2 —— 全新设计，零依赖（node 自带 zlib）。
 *
 * 设计
 * ────
 *  构图    Wi-Fi 信号弧居中做主体骨架（三道粗弧 + 实心圆点），
 *          白色八分音符插在弧的圆心位置——「音乐正从 Wi-Fi 波源播出」。
 *  配色    适配应用暗色系 UI：
 *          底    深海蓝黑斜向渐变 (#16232B → #0B1418)，与应用页面背景同族；
 *          弧线  亮青 #2DD4BF（应用主题色，暗底上高对比）；
 *          音符  纯白（视觉焦点，最亮元素）。
 *  风格    弧线粗壮、几何干净，只有「底 / 青 / 白」三层，
 *          缩到 48px 通知尺寸依然清晰。
 *
 * 输出
 * ────
 *   mipmap-<dpi>/ic_launcher.png            圆角方形（旧式）
 *   mipmap-<dpi>/ic_launcher_foreground.png 透明底自适应前景
 *   mipmap-anydpi-v26/ic_launcher.xml       自适应图标声明
 *   可选：node make_icon_v2.js <resDir> <preview.png>
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT = process.argv[2] || 'app/src/main/res';
const PREVIEW = process.argv[3] || null;
const SS = 5; // 超采样

// ═════════════════════════════ PNG 编码 ═════════════════════════════
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}
function encodePNG(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ═════════════════════════════ 调色板 ═════════════════════════════
// 适配暗色 UI（Theme.kt：背景 #0E1116、主色 #2DD4BF）
const BG_TOP = [22, 35, 43];      // #16232B 深海蓝（左上亮端）
const BG_BOT = [10, 17, 20];      // #0A1114 近黑深青（右下）
const TEAL = [45, 212, 191];      // #2DD4BF 亮青（信号弧）
const WHITE = [255, 255, 255];    // 音符

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
const lerp = (a, b, t) => a + (b - a) * t;
const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
function smoothstep(e0, e1, x) {
  const t = clamp01((x - e0) / (e1 - e0 || 1e-9));
  return t * t * (3 - 2 * t);
}

// ═════════════════════════════ 几何布局（0..1 设计坐标） ═════════════════════════════
// Wi-Fi 弧：圆心右移避开音符杆（杆在 x≈0.50），弧心 x=0.56
const WIFI = {
  cx: 0.56,
  cy: 0.74,
  dotR: 0.052,                        // 实心圆点半径
  rings: [
    { r: 0.130, hw: 0.023 },           // 内弧（粗）
    { r: 0.210, hw: 0.025 },           // 中弧
    { r: 0.290, hw: 0.027 },           // 外弧
  ],
  angFrom: 32,
  angTo: 148,
  ringEndR: 0.033,                     // 弧线端头圆帽半径（顺弧渐细）
};

// 音符：八分音符，锚在 Wi-Fi 圆心上方 —— 音符「悬浮」在波源上
const NOTE = {
  head: { cx: 0.428, cy: 0.560, rx: 0.085, ry: 0.062, rot: (-18 * Math.PI) / 180 },
  stemX: 0.502,
  stemTop: 0.255,
  stemHalfW: 0.0155,
  flag: {
    p0: [0.502, 0.262],
    c: [0.608, 0.300],
    p1: [0.568, 0.430],
    w0: 0.046,
    w1: 0.008,
  },
};

// ═════════════════════════════ 形状判定 ═════════════════════════════
function inRotEllipse(x, y, e) {
  const dx = x - e.cx;
  const dy = y - e.cy;
  const cs = Math.cos(-e.rot);
  const sn = Math.sin(-e.rot);
  const rx = dx * cs - dy * sn;
  const ry = dx * sn + dy * cs;
  return (rx * rx) / (e.rx * e.rx) + (ry * ry) / (e.ry * e.ry) <= 1;
}
function segDist(px, py, x0, y0, x1, y1) {
  const vx = x1 - x0;
  const vy = y1 - y0;
  const wx = px - x0;
  const wy = py - y0;
  const len2 = vx * vx + vy * vy;
  let t = len2 > 0 ? (wx * vx + wy * vy) / len2 : 0;
  t = clamp01(t);
  return Math.hypot(px - (x0 + t * vx), py - (y0 + t * vy));
}
function qbez(p0, c, p1, t) {
  const mt = 1 - t;
  return [
    mt * mt * p0[0] + 2 * mt * t * c[0] + t * t * p1[0],
    mt * mt * p0[1] + 2 * mt * t * c[1] + t * t * p1[1],
  ];
}
// 符尾：贝塞尔采样成渐细粗线
const FLAG_PTS = (() => {
  const pts = [];
  const f = NOTE.flag;
  for (let i = 0; i <= 72; i++) {
    const t = i / 72;
    const p = qbez(f.p0, f.c, f.p1, t);
    pts.push({ x: p[0], y: p[1], hw: lerp(f.w0, f.w1, Math.pow(t, 0.7)) / 2 });
  }
  return pts;
})();
function inNoteFlag(x, y) {
  for (let i = 0; i < FLAG_PTS.length - 1; i++) {
    const a = FLAG_PTS[i];
    const b = FLAG_PTS[i + 1];
    if (segDist(x, y, a.x, a.y, b.x, b.y) <= (a.hw + b.hw) / 2) return true;
  }
  return false;
}
function inNote(x, y) {
  const n = NOTE;
  if (inRotEllipse(x, y, n.head)) return true;
  if (segDist(x, y, n.stemX, n.head.cy - 0.01, n.stemX, n.stemTop) <= n.stemHalfW) return true;
  if (inNoteFlag(x, y)) return true;
  return false;
}

/**
 * Wi-Fi 覆盖：返回 [青色覆盖度 0..1, 白色覆盖度 0..1]。
 * 弧线主体青色；音符压在弧上方时弧让位（白色音符覆盖青弧）。
 */
function wifiLayers(x, y) {
  const w = WIFI;
  const dx = x - w.cx;
  const dy = y - w.cy;
  const dist = Math.hypot(dx, dy);
  if (dist <= w.dotR) return 1; // 圆点全青

  const ang = (Math.atan2(-dy, dx) * 180) / Math.PI;
  const soft = 2.2;
  const angCov =
    smoothstep(w.angFrom - soft, w.angFrom + soft, ang) *
    (1 - smoothstep(w.angTo - soft, w.angTo + soft, ang));
  if (angCov <= 0) return 0;

  for (let i = 0; i < w.rings.length; i++) {
    const ring = w.rings[i];
    // 沿半径粗细（端头加圆帽渐细）
    const t = (dist - (ring.r - ring.hw)) / (ring.hw * 2);
    let cov = 1 - smoothstep(0.72, 1.0, Math.abs(t - 0.5) * 2 - 0.44);
    // 端头圆帽
    if (cov < 1 && dist < ring.r) cov = Math.max(cov, 0);
    // 弧两端按角度收圆帽
    const endCapR = WIFI.ringEndR * (1 - i * 0.12);
    for (const angEnd of [w.angFrom, w.angTo]) {
      const rad = (angEnd * Math.PI) / 180;
      const ex = w.cx + Math.cos(rad) * ring.r;
      const ey = w.cy - Math.sin(rad) * ring.r;
      const d = Math.hypot(x - ex, y - ey);
      if (d <= endCapR) cov = Math.max(cov, 1 - smoothstep(endCapR * 0.6, endCapR, d));
    }
    if (cov > 0) return clamp01(cov * angCov);
  }
  return 0;
}

function roundedBox(x, y, r) {
  const cx = Math.min(Math.max(x, r), 1 - r);
  const cy = Math.min(Math.max(y, r), 1 - r);
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

// ═════════════════════════════ 渲染 ═════════════════════════════
function render(size, opts = {}) {
  const { foreground = false, square = false } = opts;
  const W = size;
  const N = W * SS;
  const acc = new Float32Array(W * W * 4);

  // 自适应前景：内容缩到中心 66% 安全区
  const inner = foreground ? 0.66 : 1.0;
  const off = (1 - inner) / 2;
  const toDesign = (px, py) => [(px / N) * inner + off, (py / N) * inner + off];

  for (let py = 0; py < N; py++) {
    for (let px = 0; px < N; px++) {
      const [x, y] = toDesign(px, py);
      if (!(square || roundedBox(x, y, 0.2235))) continue;

      // 底色：斜向渐变（左上亮 → 右下深）
      const t = clamp01((x * 0.6 + y * 0.85) / 1.45);
      let col = mix3(BG_TOP, BG_BOT, t);

      // Wi-Fi 弧（青色）
      const wc = wifiLayers(x, y);
      if (wc > 0) col = mix3(col, TEAL, wc);

      // 音符（白色，最前）
      if (inNote(x, y)) col = WHITE;

      const ti = (Math.floor(py / SS) * W + Math.floor(px / SS)) * 4;
      acc[ti] += col[0];
      acc[ti + 1] += col[1];
      acc[ti + 2] += col[2];
      acc[ti + 3] += 255;
    }
  }

  const out = Buffer.alloc(W * W * 4);
  const n = SS * SS;
  for (let i = 0; i < W * W; i++) {
    out[i * 4] = Math.round(acc[i * 4] / n);
    out[i * 4 + 1] = Math.round(acc[i * 4 + 1] / n);
    out[i * 4 + 2] = Math.round(acc[i * 4 + 2] / n);
    out[i * 4 + 3] = Math.round(acc[i * 4 + 3] / n);
  }
  return encodePNG(W, W, out);
}

// ═════════════════════════════ 输出 ═════════════════════════════
const DENSITIES = [
  ['mdpi', 48],
  ['hdpi', 72],
  ['xhdpi', 96],
  ['xxhdpi', 144],
  ['xxxhdpi', 192],
];

for (const [dpi, size] of DENSITIES) {
  const dir = path.join(OUT, 'mipmap-' + dpi);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), render(size, {}));
  fs.writeFileSync(path.join(dir, 'ic_launcher_foreground.png'), render(size, { foreground: true }));
  console.log(`  ${dpi.padEnd(8)} ${String(size).padStart(3)}px  ok`);
}

const anydpi = path.join(OUT, 'mipmap-anydpi-v26');
fs.mkdirSync(anydpi, { recursive: true });
fs.writeFileSync(
  path.join(anydpi, 'ic_launcher.xml'),
  `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
`,
);
console.log('  anydpi-v26 ic_launcher.xml');

if (PREVIEW) {
  fs.mkdirSync(path.dirname(PREVIEW), { recursive: true });
  fs.writeFileSync(PREVIEW, render(512, { square: true }));
  console.log('  preview -> ' + PREVIEW);
}
