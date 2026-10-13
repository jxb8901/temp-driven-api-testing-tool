const zlib = require('node:zlib');

const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

function paeth(left, above, upperLeft) {
  const prediction = left + above - upperLeft;
  const leftDistance = Math.abs(prediction - left);
  const aboveDistance = Math.abs(prediction - above);
  const upperLeftDistance = Math.abs(prediction - upperLeft);
  if (leftDistance <= aboveDistance && leftDistance <= upperLeftDistance) return left;
  if (aboveDistance <= upperLeftDistance) return above;
  return upperLeft;
}

function decodePng(buffer) {
  if (!buffer.subarray(0, 8).equals(PNG_SIGNATURE)) throw new Error('Visual snapshot is not a PNG image.');
  let width;
  let height;
  let bitDepth;
  let colorType;
  let interlace;
  const imageData = [];

  for (let offset = 8; offset < buffer.length;) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === 'IDAT') {
      imageData.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += length + 12;
  }

  const bytesPerPixel = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
  if (!width || !height || bitDepth !== 8 || bytesPerPixel === 0 || interlace !== 0) {
    throw new Error(`Unsupported PNG format: ${width}x${height}, depth ${bitDepth}, color type ${colorType}, interlace ${interlace}.`);
  }

  const stride = width * bytesPerPixel;
  const raw = zlib.inflateSync(Buffer.concat(imageData));
  if (raw.length !== height * (stride + 1)) throw new Error('PNG scanline data has an unexpected length.');
  const pixels = Buffer.alloc(height * stride);

  for (let y = 0; y < height; y += 1) {
    const sourceRow = y * (stride + 1);
    const destinationRow = y * stride;
    const filter = raw[sourceRow];
    for (let x = 0; x < stride; x += 1) {
      const rawByte = raw[sourceRow + 1 + x];
      const left = x >= bytesPerPixel ? pixels[destinationRow + x - bytesPerPixel] : 0;
      const above = y > 0 ? pixels[destinationRow + x - stride] : 0;
      const upperLeft = y > 0 && x >= bytesPerPixel ? pixels[destinationRow + x - stride - bytesPerPixel] : 0;
      let predictor = 0;
      if (filter === 1) predictor = left;
      else if (filter === 2) predictor = above;
      else if (filter === 3) predictor = Math.floor((left + above) / 2);
      else if (filter === 4) predictor = paeth(left, above, upperLeft);
      else if (filter !== 0) throw new Error(`Unsupported PNG filter: ${filter}.`);
      pixels[destinationRow + x] = (rawByte + predictor) & 0xff;
    }
  }

  if (colorType === 6) return { width, height, pixels };
  const rgba = Buffer.alloc(width * height * 4);
  for (let pixel = 0; pixel < width * height; pixel += 1) {
    rgba[pixel * 4] = pixels[pixel * 3];
    rgba[pixel * 4 + 1] = pixels[pixel * 3 + 1];
    rgba[pixel * 4 + 2] = pixels[pixel * 3 + 2];
    rgba[pixel * 4 + 3] = 255;
  }
  return { width, height, pixels: rgba };
}

function comparePng(expectedBuffer, actualBuffer, channelTolerance = 32) {
  const expected = decodePng(expectedBuffer);
  const actual = decodePng(actualBuffer);
  if (expected.width !== actual.width || expected.height !== actual.height) {
    return { width: actual.width, height: actual.height, changedPixels: expected.width * expected.height, changedRatio: 1 };
  }

  let changedPixels = 0;
  for (let offset = 0; offset < expected.pixels.length; offset += 4) {
    const red = Math.abs(expected.pixels[offset] - actual.pixels[offset]);
    const green = Math.abs(expected.pixels[offset + 1] - actual.pixels[offset + 1]);
    const blue = Math.abs(expected.pixels[offset + 2] - actual.pixels[offset + 2]);
    const alpha = Math.abs(expected.pixels[offset + 3] - actual.pixels[offset + 3]);
    if (Math.max(red, green, blue, alpha) > channelTolerance) changedPixels += 1;
  }
  const totalPixels = expected.width * expected.height;
  return { width: expected.width, height: expected.height, changedPixels, changedRatio: changedPixels / totalPixels };
}

module.exports = { comparePng };
