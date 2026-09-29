/** Main-thread host. Only commands cross the port. PCM stays in the AudioWorklet. */
export class BrowserAudio {
  constructor(onState = () => {}, platform = globalThis) {
    this.platform = platform;
    this.onState = onState;
    this.context = null;
    this.node = null;
    this.loading = null;
    this.revision = 0;
    this.closed = false;
    this.source = 'sine';
    this.phase = 'ready';
  }

  async play() {
    if (this.closed) return;
    const revision = ++this.revision;
    let context;
    try {
      if (!this.context) {
        const Context = this.platform.AudioContext ?? this.platform.webkitAudioContext;
        if (!Context || !this.platform.AudioWorkletNode)
          throw new Error('AudioWorklet is unavailable');
        this.context = new Context({ sampleRate: 48000, latencyHint: 'playback' });
      }
      context = this.context;
      if (!context.audioWorklet) throw new Error('AudioWorklet requires HTTPS or localhost');
      if (context.sampleRate !== 48000) throw new Error('This sample requires 48 kHz output');
      // Resume during the user gesture, before awaiting module loading.
      const resumed = context.resume();
      const loaded = this.initialize(context);
      this.update('loading');
      await Promise.all([resumed, loaded]);
      if (this.closed || revision !== this.revision || context !== this.context) return;
      this.node.port.postMessage({ type: 'play', source: this.source, revision });
      this.update('playing');
    } catch (error) {
      if (!this.closed && (!context || context === this.context)) this.fail(error);
    }
  }

  async pause() {
    if (this.closed) return;
    const revision = ++this.revision;
    const context = this.context;
    this.node?.port.postMessage({ type: 'pause', revision });
    this.update('paused');
    try {
      await context?.suspend();
    } catch (error) {
      if (!this.closed && revision === this.revision && context === this.context) this.fail(error);
    }
  }

  selectSource(source) {
    if (this.closed) return;
    if (source !== 'sine' && source !== 'noise') throw new Error('Unknown source');
    this.source = source;
    this.node?.port.postMessage({ type: 'source', source, revision: this.revision });
    this.update(this.phase);
  }

  initialize(context) {
    if (this.loading) return this.loading;
    this.loading = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('AudioWorklet loading timed out')), 10000);
      context.audioWorklet
        .addModule(new URL('./audio-worklet.mjs', import.meta.url))
        .then(() => {
          if (this.closed || context !== this.context) throw new Error('Audio context retired');
          const node = new this.platform.AudioWorkletNode(context, 'kotlin-pcm', {
            numberOfInputs: 0,
            numberOfOutputs: 1,
            outputChannelCount: [2],
            channelCount: 2,
            channelCountMode: 'explicit',
          });
          this.node = node;
          node.port.onmessage = ({ data }) => {
            if (context === this.context && data.type === 'error') this.fail(new Error(data.error));
          };
          node.addEventListener('processorerror', () => {
            if (context === this.context) this.fail(new Error('Audio processor stopped'));
          });
          node.connect(context.destination);
          resolve();
        })
        .catch(reject)
        .finally(() => clearTimeout(timeout));
    });
    return this.loading;
  }

  update(phase, error = '') {
    this.phase = phase;
    this.onState({ phase, source: this.source, error });
  }

  fail(error) {
    this.revision++;
    this.retire();
    this.update('error', error?.message ?? String(error));
  }

  retire() {
    const context = this.context;
    this.context = null;
    this.loading = null;
    if (this.node) {
      this.node.port.postMessage({ type: 'close', revision: ++this.revision });
      this.node.port.onmessage = null;
      this.node.disconnect();
      this.node.port.close();
      this.node = null;
    }
    return context?.close().catch(() => {});
  }

  async close() {
    if (this.closed) return;
    this.closed = true;
    this.revision++;
    await this.retire();
    this.update('closed');
  }
}
