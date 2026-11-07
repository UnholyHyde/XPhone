package ru.xphone.booster;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;

@android.annotation.TargetApi(29)
final class RecordingAudio implements AutoCloseable {
    private static final int RATE = 48000;
    private static final int BLOCK = 960;
    private final File file;
    private AudioRecord mic, playback;
    private MediaCodec encoder;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean paused;
    volatile boolean muted;
    private Exception failure;

    @SuppressLint("MissingPermission")
    RecordingAudio(File file, MediaProjection projection, boolean microphone, boolean system) {
        this.file = file;
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        int size = Math.max(4 * BLOCK * 2, AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
        try {
            if (microphone) mic = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format).setBufferSizeInBytes(size).build();
            if (system) {
                var capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                playback = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                        .setAudioFormat(format).setBufferSizeInBytes(size).build();
            }
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    void start() throws IOException {
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        if (mic != null) mic.startRecording();
        if (playback != null) playback.startRecording();
        running = true;
        worker = new Thread(this::encode, "XPhone-record-audio");
        worker.start();
    }

    void pause(boolean value) {
        paused = value;
    }

    private void encode() {
        short[] primary = new short[BLOCK], secondary = new short[BLOCK];
        AudioRecord source = mic == null ? playback : mic;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long samples = 0;
        int track = -1;
        boolean eos = false, done = false;
        long eosDeadline = 0;
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            while (!done) {
                if (!eos) {
                    int input = encoder.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        int count = 0;
                        if (running) {
                            count = source.read(primary, 0, BLOCK, AudioRecord.READ_BLOCKING);
                            if (count < 0 && running) throw new IOException("AudioRecord " + count);
                            if (count < 0) count = 0;
                        }
                        int extra = mic != null && playback != null && count > 0
                                ? playback.read(secondary, 0, count, AudioRecord.READ_NON_BLOCKING) : 0;
                        if (extra < 0 && running) throw new IOException("Playback AudioRecord " + extra);
                        var buffer = encoder.getInputBuffer(input);
                        buffer.clear();
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        if (paused || !running) count = 0;
                        count = Math.min(count, buffer.remaining() / 2);
                        for (int i = 0; i < count; i++) {
                            int value = mic != null && muted ? 0 : primary[i];
                            if (i < extra) value += secondary[i];
                            buffer.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
                        }
                        eos = !running;
                        if (eos) eosDeadline = android.os.SystemClock.elapsedRealtime() + 2500;
                        encoder.queueInputBuffer(input, 0, count * 2, samples * 1000000 / RATE,
                                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        samples += count;
                    }
                }
                int output;
                while ((output = encoder.dequeueOutputBuffer(info, eos ? 10000 : 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                    } else if (output >= 0) {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                            muxer.writeSampleData(track, encoder.getOutputBuffer(output), info);
                        done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(output, false);
                        if (done) break;
                    }
                }
                if (eos && !done && android.os.SystemClock.elapsedRealtime() >= eosDeadline)
                    throw new IOException("Audio encoder did not finish");
            }
            if (track >= 0) muxer.stop();
        } catch (Exception error) {
            failure = error;
        } finally {
            if (muxer != null) muxer.release();
        }
    }

    void finish() throws Exception {
        running = false;
        stopInput(mic);
        stopInput(playback);
        if (worker != null) {
            worker.join(4000);
            if (worker.isAlive()) throw new IOException("Audio encoder still running");
        }
        if (failure != null) throw failure;
    }

    private static void stopInput(AudioRecord input) {
        if (input != null && input.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) input.stop();
    }

    @Override public void close() {
        running = false;
        try { stopInput(mic); } catch (RuntimeException ignored) {}
        try { stopInput(playback); } catch (RuntimeException ignored) {}
        if (mic != null) mic.release();
        if (playback != null) playback.release();
        if (encoder != null) encoder.release();
    }
}
