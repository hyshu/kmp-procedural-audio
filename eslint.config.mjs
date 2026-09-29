import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import globals from 'globals';

export default defineConfig([
  {
    ignores: ['**/build/**', '**/dist/**', '**/node_modules/**', '.gradle/**', '.venv/**'],
  },
  js.configs.recommended,
  {
    files: [
      'eslint.config.mjs',
      'playwright.config.mjs',
      'samples/web/*.test.mjs',
      'samples/web/*.spec.mjs',
      'samples/web/stage.mjs',
    ],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['samples/web/browser-audio.mjs'],
    languageOptions: { globals: globals.browser },
  },
  {
    files: ['samples/web/audio-worklet.mjs'],
    languageOptions: {
      globals: {
        ...globals.worker,
        AudioWorkletProcessor: 'readonly',
        registerProcessor: 'readonly',
        sampleRate: 'readonly',
      },
    },
  },
]);
