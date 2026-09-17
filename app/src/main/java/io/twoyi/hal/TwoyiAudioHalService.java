/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.hal;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.IOUtils;

/**
 * Host-side Audio HAL Service.
 * Listens for PCM stream from Guest Android OS AudioFlinger and renders it via host AudioTrack.
 */
public class TwoyiAudioHalService {

    private static final String TAG = "TwoyiAudioHalService";

    private final Context mContext;
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private AudioTrack mAudioTrack;
    private int mCurrentSampleRate = 44100;
    private int mCurrentChannels = 2;
    private float mCurrentVolume = 1.0f;
    private LocalServerSocket mServerSocket;

    public TwoyiAudioHalService(Context context) {
        mContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (mRunning.compareAndSet(false, true)) {
            mExecutor.submit(this::serverLoop);
            Log.i(TAG, "Twoyi Audio HAL Service started");
        }
    }

    public synchronized void stop() {
        mRunning.set(false);
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {}
            mServerSocket = null;
        }
        releaseAudioTrack();
        Log.i(TAG, "Twoyi Audio HAL Service stopped");
    }

    private void serverLoop() {
        LocalSocket serverSocketRaw = null;
        try {
            serverSocketRaw = new LocalSocket(LocalSocket.SOCKET_STREAM);
            serverSocketRaw.bind(new LocalSocketAddress(TwoyiHalProtocol.SOCK_AUDIO, LocalSocketAddress.Namespace.ABSTRACT));
            mServerSocket = new LocalServerSocket(serverSocketRaw.getFileDescriptor());

            Log.i(TAG, "Audio HAL server listening on: " + TwoyiHalProtocol.SOCK_AUDIO);

            while (mRunning.get()) {
                LocalSocket client = mServerSocket.accept();
                Log.i(TAG, "Guest Audio HAL connected");
                mExecutor.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                Log.e(TAG, "Audio server loop error: " + e.getMessage());
            }
        } finally {
            IOUtils.closeSilently(serverSocketRaw);
        }
    }

    private void handleClient(LocalSocket client) {
        try (DataInputStream dis = new DataInputStream(client.getInputStream())) {
            byte[] pcmBuffer = new byte[8192];

            while (mRunning.get()) {
                int magic = TwoyiHalProtocol.readIntLE(dis);
                if (magic != TwoyiHalProtocol.AUDIO_MAGIC) {
                    Log.w(TAG, "Invalid audio magic: 0x" + Integer.toHexString(magic));
                    break;
                }

                int cmd = TwoyiHalProtocol.readIntLE(dis);
                int sampleRate = TwoyiHalProtocol.readIntLE(dis);
                int channels = TwoyiHalProtocol.readIntLE(dis);
                int format = TwoyiHalProtocol.readIntLE(dis);
                int payloadLen = TwoyiHalProtocol.readIntLE(dis);

                switch (cmd) {
                    case TwoyiHalProtocol.CMD_AUDIO_WRITE_DATA:
                        if (payloadLen > 0) {
                            if (pcmBuffer.length < payloadLen) {
                                pcmBuffer = new byte[payloadLen];
                            }
                            dis.readFully(pcmBuffer, 0, payloadLen);
                            ensureAudioTrack(sampleRate, channels);
                            if (mAudioTrack != null && mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                mAudioTrack.write(pcmBuffer, 0, payloadLen);
                            }
                        }
                        break;

                    case TwoyiHalProtocol.CMD_AUDIO_SET_VOLUME:
                        mCurrentVolume = sampleRate / 100.0f; // stored in sampleRate field
                        if (mAudioTrack != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                mAudioTrack.setVolume(mCurrentVolume);
                            } else {
                                mAudioTrack.setStereoVolume(mCurrentVolume, mCurrentVolume);
                            }
                        }
                        break;

                    case TwoyiHalProtocol.CMD_AUDIO_PAUSE:
                        if (mAudioTrack != null && mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            mAudioTrack.pause();
                        }
                        break;

                    case TwoyiHalProtocol.CMD_AUDIO_RESUME:
                        if (mAudioTrack != null && mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                            mAudioTrack.play();
                        }
                        break;

                    case TwoyiHalProtocol.CMD_AUDIO_CLOSE:
                        releaseAudioTrack();
                        break;

                    default:
                        if (payloadLen > 0) {
                            dis.skipBytes(payloadLen);
                        }
                        break;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Audio client disconnected: " + e.getMessage());
        } finally {
            IOUtils.closeSilently(client);
        }
    }

    private synchronized void ensureAudioTrack(int sampleRate, int channels) {
        if (sampleRate <= 0) sampleRate = 44100;
        if (channels <= 0) channels = 2;

        if (mAudioTrack != null && sampleRate == mCurrentSampleRate && channels == mCurrentChannels) {
            if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                mAudioTrack.play();
            }
            return;
        }

        releaseAudioTrack();

        mCurrentSampleRate = sampleRate;
        mCurrentChannels = channels;

        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBufSize * 2, 8192);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build();

        mAudioTrack = new AudioTrack(
                attributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mAudioTrack.setVolume(mCurrentVolume);
        } else {
            mAudioTrack.setStereoVolume(mCurrentVolume, mCurrentVolume);
        }

        mAudioTrack.play();
        Log.i(TAG, "AudioTrack initialized: " + sampleRate + "Hz, channels=" + channels);
    }

    private synchronized void releaseAudioTrack() {
        if (mAudioTrack != null) {
            try {
                if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mAudioTrack.stop();
                }
                mAudioTrack.release();
            } catch (Throwable ignored) {}
            mAudioTrack = null;
        }
    }
}
