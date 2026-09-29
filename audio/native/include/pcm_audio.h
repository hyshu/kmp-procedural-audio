#ifndef PCM_AUDIO_H
#define PCM_AUDIO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct pcm_audio_output pcm_audio_output;

/* Writes interleaved stereo samples at 48000 Hz. Return 1 on success.
 * The callback runs on the output thread. Do not invoke lifecycle functions
 * from this callback. The sample pointer is valid only during this call.
 */
typedef int (*pcm_audio_render_fn)(void *user_data, float *samples, int32_t frames);

/* Returns NULL on failure and writes a bounded UTF-8 error message. */
pcm_audio_output *pcm_audio_create(pcm_audio_render_fn render, void *user_data, char *error,
                                   size_t error_capacity);

/* Return 1 on success. Device failures can arrive after start returns.
 * Stop waits for the output thread and every active callback to finish.
 */
int pcm_audio_start(pcm_audio_output *output);
int pcm_audio_stop(pcm_audio_output *output);

/* Stops and frees the output. Return 0 if called by its own render callback.
 * The caller must not use the pointer after a successful destroy.
 * All caller access must finish before destruction begins.
 */
int pcm_audio_destroy(pcm_audio_output *output);

/* Copies and consumes the latest error. Returns 0 when no error is pending.
 * A missing destination or a zero capacity leaves the error pending.
 */
int pcm_audio_take_error(pcm_audio_output *output, char *error, size_t error_capacity);

#ifdef __cplusplus
}
#endif

#endif
