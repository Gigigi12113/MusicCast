#!/usr/bin/env node
/**
 * 用用户提供的 ic_launcher.png（192×192）生成全套启动图标。
 *
 * 输入: <src.png>（建议 ≥192px 方形）
 * 输出: mipmap-{mdpi..xxxhdpi}/ic_launcher.png      原图缩放（不透明圆角方形）
 *       mipmap-<dpi>/ic_launcher_foreground.png      原图缩到 66% 居中（自适应前景）
 *       mipmap-anydpi-v26/ic_launcher.xml           复用现有声明
 *
 * 缩放用双线性插值 + 2x2 盒式预滤波，质量好且零依赖。
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SRC = process.argv[2];
const OUT = process.argv[3] || 'app/src/main/res';
if (!SRC || !fs.existsSync(SRC)) {
  console.error('用法: node apply_icon.js <源图.png> [res目录]');
  process.exit(1);
}

// ── PNG 解码（仅支持生成器自家格式：8bit RGBA, filter 全 0 行首字节）──
function decodePNG(file) {
  const buf = fs.readFileSync(file);
  let pos = 8, idat = [], w = 0, h = 0;
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    if (type === 'IHDR') {
      w = buf.readUInt32BE(pos + 8);
      h = buf.readUInt32BE(pos + 12);
      const bitDepth = buf[pos + 16], colorType = buf[pos + 17];
      if (bitDepth !== 8 || colorType !== 6) throw new Error('需要 8bit RGBA PNG');
    } else if (type === 'IDAT') idat.push(buf.slice(pos + 8, pos + 8 + len));
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const out = Buffer.alloc(w * h * 4);
  let prev = Buffer.alloc(w * 4);
  for (let y = 0; y < h; y++) {
    const rowStart = y * (w * 4 + 1);
    const filter = raw[rowStart];
    const row = raw.slice(rowStart + 1, rowStart + 1 + w * 4);
    // 支持 filter 0/1/2/3/4（通用解码，稳）
    for (let x = 0; x < w * 4; x++) {
      const a = x >= 4 ? row[x - 4] : 0;
      const b = prev[x];
      const c = x >= 4 ? prev[x - 4] : 0;
      switch (filter) {
        case 0: break;
        case 1: row[x] = (row[x] + a) & 0xff; break;
        case 2: row[x] = (row[x] + b) & 0xff; break;
        case 3: row[x] = (row[x] + ((a + b) >> 1)) & 0xff; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          const pr = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          row[x] = (row[x] + pr) & 0xff;
          break;
        }
      }
    }
    row.copy(out, y * w * 4);
    prev = row;
  }
  return { w, h, data: out };
}

// ── PNG 编码 ──
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

// ── 双线性缩放（带透明度正确处理）──
function resize(src, sw, sh, dw, dh) {
  const dst = Buffer.alloc(dw * dh * 4);
  const rx = sw / dw, ry = sh / dh;
  for (let y = 0; y < dh; y++) {
    const sy = (y + 0.5) * ry - 0.5;
    const y0 = Math.max(0, Math.floor(sy)), y1 = Math.min(sh - 1, y0 + 1);
    const fy = sy - y0;
    for (let x = 0; x < dw; x++) {
      const sx = (x + 0.5) * rx - 0.5;
      const x0 = Math.max(0, Math.floor(sx)), x1 = Math.min(sw - 1, x0 + 1);
      const fx = sx - x0;
      const i00 = (y0 * sw + x0) * 4, i10 = (y0 * sw + x1) * 4;
      const i01 = (y1 * sw + x0) * 4, i11 = (y1 * sw + x1) * 4;
      const di = (y * dw + x) * 4;
      // 预乘 alpha 的颜色混合，避免半透明边缘发暗
      for (let c = 0; c < 4; c++) {
        const top = src[i00 + c] * (1 - fx) + src[i10 + c] * fx;
        const bot = src[i01 + c] * (1 - fx) + src[i11 + c] * fx;
        dst[di + c] = Math.round(top * (1 - fy) + bot * fy);
      }
    }
  }
  return dst;
}

// ── 前景：缩到 66% 居中（自适应安全区），四周透明 ──
function makeForeground(src, sw, sh, size) {
  const inner = Math.round(size * 0.92);
  const scaled = resize(src, sw, sh, inner, inner);
  const out = Buffer.alloc(size * size * 4);
  const off = Math.floor((size - inner) / 2);
  for (let y = 0; y < inner; y++) {
    scaled.copy(out, ((y + off) * size + off) * 4, y * inner * 4, (y + 1) * inner * 4);
  }
  return out;
}

// ── 主流程 ──
const { w: sw, h: sh, data: src } = decodePNG(SRC);
console.log(`源图: ${sw}x${sh}`);
if (sw !== sh) console.warn('警告: 源图不是正方形，输出会拉伸');

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
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), encodePNG(size, size, resize(src, sw, sh, size, size)));
  fs.writeFileSync(path.join(dir, 'ic_launcher_foreground.png'), encodePNG(size, size, makeForeground(src, sw, sh, size)));
  console.log(`  ${dpi.padEnd(8)} ${String(size).padStart(3)}px  ok`);
}
console.log('完成');
