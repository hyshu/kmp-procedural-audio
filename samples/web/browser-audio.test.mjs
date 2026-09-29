import test from 'node:test';
import assert from 'node:assert/strict';
import { BrowserAudio } from './browser-audio.mjs';

function harness() {
  const contexts = [];
  const nodes = [];
  const states = [];
  class Context {
    constructor(options) {
      this.sampleRate = options.sampleRate;
      this.destination = {};
      this.calls = [];
      this.module = new Promise((resolve, reject) => {
        this.loaded = resolve;
        this.rejected = reject;
      });
      this.audioWorklet = { addModule: () => this.module };
      contexts.push(this);
    }
    resume() {
      this.calls.push('resume');
      return Promise.resolve();
    }
    suspend() {
      this.calls.push('suspend');
      return Promise.resolve();
    }
    close() {
      this.calls.push('close');
      return Promise.resolve();
    }
  }
  class Node {
    constructor(context) {
      this.context = context;
      this.events = {};
      this.messages = [];
      this.port = {
        postMessage: (data) => this.messages.push(data),
        close: () => {
          this.portClosed = true;
        },
      };
      nodes.push(this);
    }
    addEventListener(name, listener) {
      this.events[name] = listener;
    }
    connect() {
      this.connected = true;
    }
    disconnect() {
      this.connected = false;
    }
  }
  const audio = new BrowserAudio((state) => states.push(state), {
    AudioContext: Context,
    AudioWorkletNode: Node,
  });
  return { audio, contexts, nodes, states };
}

test('resume runs during the gesture and PCM is never posted', async () => {
  const { audio, contexts, nodes } = harness();
  const playing = audio.play();
  assert.deepEqual(contexts[0].calls, ['resume']);
  contexts[0].loaded();
  await playing;
  assert.deepEqual(nodes[0].messages, [{ type: 'play', source: 'sine', revision: 1 }]);
  audio.selectSource('noise');
  assert.deepEqual(nodes[0].messages[1], { type: 'source', source: 'noise', revision: 1 });
  await audio.close();
  assert.equal(nodes[0].portClosed, true);
  assert.equal(nodes[0].connected, false);
  assert.ok(contexts[0].calls.includes('close'));
});

test('pause during worklet loading cannot be undone by late play', async () => {
  const { audio, contexts, nodes } = harness();
  const playing = audio.play();
  await audio.pause();
  contexts[0].loaded();
  await playing;
  assert.equal(audio.phase, 'paused');
  assert.deepEqual(nodes[0].messages, []);
  assert.ok(contexts[0].calls.includes('suspend'));
  await audio.close();
});

test('last play and source choice win during loading', async () => {
  const { audio, contexts, nodes } = harness();
  const first = audio.play();
  await audio.pause();
  audio.selectSource('noise');
  const latest = audio.play();
  contexts[0].loaded();
  await Promise.all([first, latest]);
  assert.deepEqual(nodes[0].messages, [{ type: 'play', source: 'noise', revision: 3 }]);
  await audio.close();
});

test('close during loading never connects a late node', async () => {
  const { audio, contexts, nodes } = harness();
  const playing = audio.play();
  await audio.close();
  contexts[0].loaded();
  await playing;
  assert.equal(nodes.length, 0);
  assert.equal(audio.phase, 'closed');
});

test('module failure closes its context and a later gesture can retry', async () => {
  const { audio, contexts, nodes } = harness();
  const first = audio.play();
  contexts[0].rejected(new Error('Module unavailable'));
  await first;
  assert.equal(audio.phase, 'error');
  assert.ok(contexts[0].calls.includes('close'));
  const second = audio.play();
  contexts[1].loaded();
  await second;
  assert.equal(audio.phase, 'playing');
  assert.equal(nodes.length, 1);
  await audio.close();
});

test('processor errors release the graph and report failure', async () => {
  const { audio, contexts, nodes, states } = harness();
  const playing = audio.play();
  contexts[0].loaded();
  await playing;
  nodes[0].events.processorerror();
  assert.equal(audio.context, null);
  assert.equal(nodes[0].connected, false);
  assert.equal(nodes[0].portClosed, true);
  assert.equal(states.at(-1).phase, 'error');
});
