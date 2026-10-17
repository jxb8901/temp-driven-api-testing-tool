// jsdom does not implement Range geometry. CodeMirror probes it while measuring
// the Load YAML preview; without these methods the measurement loop repeatedly
// throws and can push otherwise valid tests past Vitest's default timeout.
if (typeof Range !== 'undefined') {
  if (typeof Range.prototype.getClientRects !== 'function') {
    Object.defineProperty(Range.prototype, 'getClientRects', {
      configurable: true,
      value: () => [] as unknown as DOMRectList,
    });
  }
  if (typeof Range.prototype.getBoundingClientRect !== 'function') {
    Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
      configurable: true,
      value: () => new DOMRect(0, 0, 0, 0),
    });
  }
}
