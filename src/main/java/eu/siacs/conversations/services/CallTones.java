package eu.siacs.conversations.services;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.xmpp.manager.JingleManager;
import java.util.concurrent.TimeUnit;

/**
 * Короткие сигналы во время разговора. Звучат в том же динамике, что и голос собеседника: поток
 * разговора идет на текущее устройство вывода звонка.
 */
public final class CallTones {

    public enum Tone {
        // две ноты вниз: связь с собеседником пропала
        CONNECTIVITY_LOST(660, 440),
        // две ноты вверх: связь вернулась
        CONNECTIVITY_RESTORED(660, 880);

        final int[] frequencies;

        Tone(final int... frequencies) {
            this.frequencies = frequencies;
        }
    }

    static final int SAMPLE_RATE = 48_000;
    static final int NOTE_MILLIS = 140;
    static final int GAP_MILLIS = 60;
    private static final int FADE_MILLIS = 15;
    // около -10 dBFS: сигнал слышен, но не заглушает голос
    static final double AMPLITUDE = 0.3;

    private CallTones() {}

    public static void play(final Tone tone) {
        JingleManager.SCHEDULED_EXECUTOR_SERVICE.execute(() -> playNow(tone));
    }

    private static void playNow(final Tone tone) {
        final short[] samples = pcm(tone);
        final AudioTrack track;
        try {
            track =
                    new AudioTrack.Builder()
                            .setAudioAttributes(
                                    new AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                            .setContentType(
                                                    AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                            .build())
                            .setAudioFormat(
                                    new AudioFormat.Builder()
                                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                            .setSampleRate(SAMPLE_RATE)
                                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                            .build())
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(samples.length * 2)
                            .build();
        } catch (final UnsupportedOperationException | IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "unable to create audio track for " + tone, e);
            return;
        }
        try {
            track.write(samples, 0, samples.length);
            track.play();
        } catch (final IllegalStateException e) {
            Log.w(Config.LOGTAG, "unable to play " + tone, e);
            track.release();
            return;
        }
        final long millis = samples.length * 1000L / SAMPLE_RATE;
        JingleManager.SCHEDULED_EXECUTOR_SERVICE.schedule(
                track::release, millis + 200, TimeUnit.MILLISECONDS);
    }

    /** Ноты подряд с паузой; края каждой сглажены, чтобы не было щелчков. */
    static short[] pcm(final Tone tone) {
        final int note = SAMPLE_RATE * NOTE_MILLIS / 1000;
        final int gap = SAMPLE_RATE * GAP_MILLIS / 1000;
        final int fade = SAMPLE_RATE * FADE_MILLIS / 1000;
        final short[] samples = new short[tone.frequencies.length * (note + gap)];
        int offset = 0;
        for (final int frequency : tone.frequencies) {
            for (int i = 0; i < note; i++) {
                final int edge = Math.min(i, note - 1 - i);
                final double envelope =
                        edge < fade ? 0.5 - 0.5 * Math.cos(Math.PI * edge / fade) : 1;
                samples[offset + i] =
                        (short)
                                Math.round(
                                        Short.MAX_VALUE
                                                * AMPLITUDE
                                                * envelope
                                                * Math.sin(
                                                        2 * Math.PI * frequency * i / SAMPLE_RATE));
            }
            offset += note + gap;
        }
        return samples;
    }
}
