package com.p2pclient.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays raw PCM audio frames.
 */
public class AudioPlayer {
    private static final Logger logger = LoggerFactory.getLogger(AudioPlayer.class);

    private SourceDataLine line;
    private AudioFormat currentFormat;
    private final AtomicBoolean muted = new AtomicBoolean(false);

    public void setMuted(boolean mute) {
        muted.set(mute);
    }

    public boolean isMuted() {
        return muted.get();
    }

    public synchronized void play(byte[] data, int sampleRate, int channels) {
        if (data == null || data.length == 0) {
            return;
        }
        AudioFormat format = buildFormat(sampleRate, channels);
        try {
            ensureLine(format);
            if (line == null || !line.isOpen()) {
                return;
            }
            if (!muted.get()) {
                line.write(data, 0, data.length);
            }
        } catch (Exception e) {
            logger.error("Audio playback error", e);
            close();
        }
    }

    public synchronized void close() {
        if (line != null) {
            try {
                line.drain();
                line.stop();
                line.close();
            } catch (Exception ignored) { }
            line = null;
        }
    }

    private void ensureLine(AudioFormat format) throws LineUnavailableException {
        if (line != null && line.isOpen() && format.matches(currentFormat)) {
            return;
        }
        close();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();
        currentFormat = format;
        logger.info("Audio playback started: {} Hz, {} ch", (int) format.getSampleRate(), format.getChannels());
    }

    private AudioFormat buildFormat(int sampleRate, int channels) {
        int rate = sampleRate > 0 ? sampleRate : 16000;
        int ch = channels > 0 ? channels : 1;
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false);
    }
}
