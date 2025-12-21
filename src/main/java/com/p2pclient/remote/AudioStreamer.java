package com.p2pclient.remote;

import com.p2pclient.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Captures microphone audio and pushes raw PCM frames via the provided sender.
 */
public class AudioStreamer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(AudioStreamer.class);

    private final ExecutorService executor;
    private final Supplier<Boolean> sessionAlive;
    private final Consumer<P2PMessage> sender;
    private final AudioFormat format;
    private final int chunkMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private TargetDataLine line;
    private int sequence = 0;

    public AudioStreamer(ExecutorService executor,
                         Supplier<Boolean> sessionAlive,
                         Consumer<P2PMessage> sender,
                         AudioFormat format,
                         int chunkMillis) {
        this.executor = executor;
        this.sessionAlive = sessionAlive;
        this.sender = sender;
        this.format = format;
        this.chunkMillis = chunkMillis;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this);
        }
    }

    public void stop() {
        running.set(false);
        closeLine();
    }

    @Override
    public void run() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            logger.info("Audio capture started: {} Hz, {} ch, {} ms chunks", (int) format.getSampleRate(), format.getChannels(), chunkMillis);

            int bytesPerFrame = format.getFrameSize();
            int framesPerChunk = Math.max(1, (int) Math.round(format.getFrameRate() * chunkMillis / 1000.0));
            int bytesPerChunk = framesPerChunk * bytesPerFrame;
            byte[] buffer = new byte[bytesPerChunk];

            while (running.get() && sessionAlive.get()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                byte[] payload = buffer;
                if (read != buffer.length) {
                    payload = new byte[read];
                    System.arraycopy(buffer, 0, payload, 0, read);
                }
                P2PMessage msg = new P2PMessage(P2PMessage.TYPE_AUDIO, payload);
                msg.setAudioSampleRate((int) format.getSampleRate());
                msg.setAudioChannels(format.getChannels());
                msg.setAudioChunkMillis(chunkMillis);
                msg.setAudioSequence(sequence++);
                sender.accept(msg);
            }
        } catch (LineUnavailableException e) {
            logger.error("Microphone not available: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Error in audio capture loop", e);
        } finally {
            closeLine();
            logger.info("Audio capture stopped");
        }
    }

    private void closeLine() {
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) { }
            line = null;
        }
    }
}
