import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { WebAudioEngine } from './dist/pcm-audio.mjs';

function render(engine, frames) {
  const left = new Float32Array(frames);
  const right = new Float32Array(frames);
  engine.renderChannels(left, right);
  return { left, right };
}

function assertSilent(channels) {
  for (const channel of Object.values(channels)) assert.ok(channel.every((value) => value === 0));
}

test('compiled Kotlin produces finite audible stereo sine samples', () => {
  const engine = new WebAudioEngine();
  engine.play();
  const { left, right } = render(engine, 4096);
  assert.ok(left.every((value) => Number.isFinite(value) && Math.abs(value) <= 0.121));
  assert.ok(left.some((value) => value > 0.1));
  assert.ok(left.some((value) => value < -0.1));
  assert.deepEqual(left, right);
  assert.equal(engine.takeFailureMessage(), null);
  engine.close();
});

test('pause emits silence and resume retains the source position', () => {
  const engine = new WebAudioEngine();
  const uninterrupted = new WebAudioEngine();
  engine.play();
  uninterrupted.play();
  assert.deepEqual(render(engine, 257), render(uninterrupted, 257));
  engine.pause();
  assertSilent(render(engine, 513));
  engine.play();
  assert.deepEqual(render(engine, 513), render(uninterrupted, 513));
  engine.close();
  uninterrupted.close();
});

test('source replacement starts continuously and reaches independent stereo noise', () => {
  const engine = new WebAudioEngine();
  const sine = new WebAudioEngine();
  engine.play();
  sine.play();
  render(engine, 128);
  render(sine, 128);
  engine.selectSource('noise');
  const noise = render(engine, 4096);
  const reference = render(sine, 4096);
  // The first crossfade frame has zero contribution from the new source.
  assert.equal(noise.left[0], reference.left[0]);
  assert.ok(noise.left.every(Number.isFinite));
  assert.ok(noise.right.every(Number.isFinite));
  assert.ok(noise.left.slice(3000).some((value, index) => value !== noise.right[index + 3000]));
  assert.ok(noise.left.slice(3000).every((value) => Math.abs(value) <= 0.081));
  engine.close();
  sine.close();
});

test('worklet channel conversion preserves output across callback sizes', () => {
  const contiguous = new WebAudioEngine();
  const chunked = new WebAudioEngine();
  for (const engine of [contiguous, chunked]) {
    engine.play();
    engine.selectSource('noise');
  }
  const expected = render(contiguous, 8192);
  const actual = { left: new Float32Array(8192), right: new Float32Array(8192) };
  const sizes = [1, 127, 257, 1025, 2048];
  let offset = 0;
  let index = 0;
  while (offset < 8192) {
    const frames = Math.min(sizes[index++ % sizes.length], 8192 - offset);
    const block = render(chunked, frames);
    actual.left.set(block.left, offset);
    actual.right.set(block.right, offset);
    offset += frames;
  }
  assert.deepEqual(actual, expected);
  contiguous.close();
  chunked.close();
});

test('invalid stereo shape is silent, reports failure once and stops rendering', () => {
  const engine = new WebAudioEngine();
  engine.play();
  const left = new Float32Array(128).fill(0.5);
  const right = new Float32Array(64).fill(0.5);
  engine.renderChannels(left, right);
  assertSilent({ left, right });
  assert.match(engine.takeFailureMessage(), /same length/);
  assert.equal(engine.takeFailureMessage(), null);
  assertSilent(render(engine, 128));
  engine.close();
});

test('closed compiled engine is silent and cannot restart', () => {
  const engine = new WebAudioEngine();
  engine.play();
  render(engine, 128);
  engine.close();
  engine.close();
  assertSilent(render(engine, 128));
  assert.throws(() => engine.play(), /closed/);
});

// Exercise the shipping processor glue with the real compiled Kotlin engine.
// Only the browser host interface and output channel allocation are stubbed.
const originalProcessor = globalThis.AudioWorkletProcessor;
const originalRegister = globalThis.registerProcessor;
const originalSampleRate = globalThis.sampleRate;
let Processor;
globalThis.sampleRate = 48000;
globalThis.AudioWorkletProcessor = class {
  constructor() {
    this.messages = [];
    this.port = { postMessage: (message) => this.messages.push(message) };
  }
};
globalThis.registerProcessor = (name, constructor) => {
  assert.equal(name, 'kotlin-pcm');
  Processor = constructor;
};
await import('./dist/audio-worklet.mjs');
after(() => {
  globalThis.AudioWorkletProcessor = originalProcessor;
  globalThis.registerProcessor = originalRegister;
  globalThis.sampleRate = originalSampleRate;
});

function process(processor, frames = 128) {
  const left = new Float32Array(frames);
  const right = new Float32Array(frames);
  const active = processor.process([], [[left, right]]);
  return { active, channels: { left, right } };
}

test('real compiled processor ignores stale play and shuts down on close', () => {
  const processor = new Processor();
  assertSilent(process(processor).channels);
  processor.receive({ type: 'play', source: 'sine', revision: 1 });
  assert.ok(process(processor).channels.left.some((value) => Math.abs(value) > 0.01));
  processor.receive({ type: 'pause', revision: 2 });
  processor.receive({ type: 'play', source: 'noise', revision: 1 });
  assertSilent(process(processor).channels);
  processor.receive({ type: 'play', source: 'noise', revision: 3 });
  assert.ok(process(processor, 4096).channels.left.some((value) => Math.abs(value) > 0.01));
  processor.receive({ type: 'close', revision: 4 });
  const stopped = process(processor);
  assert.equal(stopped.active, false);
  assertSilent(stopped.channels);
  assert.deepEqual(processor.messages, []);
});

test('processor forwards Kotlin source errors and retires the engine', () => {
  const processor = new Processor();
  processor.receive({ type: 'source', source: 'invalid', revision: 1 });
  assert.equal(process(processor).active, false);
  assert.equal(processor.messages.length, 1);
  assert.equal(processor.messages[0].type, 'error');
  assert.match(processor.messages[0].error, /Unknown source/);
});

test('processor rejects a sample rate that would change pitch', () => {
  globalThis.sampleRate = 44100;
  try {
    assert.throws(() => new Processor(), /48 kHz/);
  } finally {
    globalThis.sampleRate = 48000;
  }
});
