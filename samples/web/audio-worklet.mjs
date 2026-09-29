import { WebAudioEngine } from './pcm-audio.mjs';

class KotlinAudioProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    if (sampleRate !== 48000) throw new Error('This sample requires a 48 kHz AudioContext');
    this.engine = new WebAudioEngine();
    this.closed = false;
    this.revision = 0;
    this.source = 'sine';
    this.port.onmessage = ({ data }) => this.receive(data);
  }

  receive(message) {
    if (this.closed || !Number.isInteger(message.revision) || message.revision < this.revision)
      return;
    this.revision = message.revision;
    try {
      switch (message.type) {
        case 'play':
          if (message.source !== this.source) this.selectSource(message.source);
          this.engine.play();
          break;
        case 'pause':
          this.engine.pause();
          break;
        case 'source':
          this.selectSource(message.source);
          break;
        case 'close':
          this.engine.close();
          this.closed = true;
          break;
        default:
          return;
      }
    } catch (error) {
      this.fail(error);
    }
  }

  selectSource(source) {
    if (source === this.source) return;
    this.engine.selectSource(source);
    this.source = source;
  }

  fail(error) {
    if (this.closed) return;
    this.engine.close();
    this.closed = true;
    this.port.postMessage({ type: 'error', error: String(error) });
  }

  process(_inputs, outputs) {
    if (this.closed) return false;
    const [left, right] = outputs[0] ?? [];
    if (!left || !right) return true;
    try {
      this.engine.renderChannels(left, right);
      const failure = this.engine.takeFailureMessage();
      if (failure) this.fail(failure);
    } catch (error) {
      left.fill(0);
      right.fill(0);
      this.fail(error);
    }
    return !this.closed;
  }
}

registerProcessor('kotlin-pcm', KotlinAudioProcessor);
