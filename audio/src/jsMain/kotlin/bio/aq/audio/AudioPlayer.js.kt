package bio.aq.audio

/**
 * Fills the stereo channel arrays supplied by an AudioWorkletProcessor.
 *
 * Create the player inside the worklet and use a 48 kHz AudioContext. Both arrays
 * must have the same length. Rendering failures silence the arrays and pause
 * output. Read [AudioPlayer.takeFailure] to consume the failure.
 */
fun AudioPlayer.renderChannels(left: dynamic, right: dynamic) = output.renderChannels(left, right)
