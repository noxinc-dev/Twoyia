/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.hal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.IOUtils;

/**
 * Host-side Microphone HAL Service.
 * Captures microphone audio using host AudioRecord and streams PCM to the Guest OS AudioFlinger.
 */
public class TwoyiMicHalService {

    private static final String TAG = "TwoyiMicHalService";

    private final Context mContext;
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private AudioRecord mAudioRecord;
    private LocalServerSocket mServerSocket;
    private int mSampleRate = 44100;
    private int mChannels = 1;

    public TwoyiMicHalService(Context context) {
        mContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (mRunning.compareAndSet(false, true)) {
            mExecutor.submit(this::serverLoop);
            Log.i(TAG, "Twoyi Microphone HAL Service started");
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
        releaseAudioRecord();
        Log.i(TAG, "Twoyi Microphone HAL Service stopped");
    }

    private void serverLoop() {
        LocalSocket serverSocketRaw = null;
        try {
            serverSocketRaw = new LocalSocket(LocalSocket.SOCKET_STREAM);
            serverSocketRaw.bind(new LocalSocketAddress(TwoyiHalProtocol.SOCK_MIC, LocalSocketAddress.Namespace.ABSTRACT));
            mServerSocket = new LocalServerSocket(serverSocketRaw.getFileDescriptor());

            Log.i(TAG, "Mic HAL server listening on: " + TwoyiHalProtocol.SOCK_MIC);

            while (mRunning.get()) {
                LocalSocket client = mServerSocket.accept();
                Log.i(TAG, "Guest Mic HAL connected");
                mExecutor.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                Log.e(TAG, "Mic server loop error: " + e.getMessage());
            }
        } finally {
            IOUtils.closeSilently(serverSocketRaw);
        }
    }

    private void handleClient(LocalSocket client) {
        try (DataInputStream dis = new DataInputStream(client.getInputStream());
             DataOutputStream dos = new DataOutputStream(client.getOutputStream())) {

            byte[] buffer = new byte[4096];

            while (mRunning.get()) {
                int magic = TwoyiHalProtocol.readIntLE(dis);
                if (magic != TwoyiHalProtocol.MIC_MAGIC) {
                    Log.w(TAG, "Invalid mic magic: 0x" + Integer.toHexString(magic));
                    break;
                }

                int cmd = TwoyiHalProtocol.readIntLE(dis);
                int sampleRate = TwoyiHalProtocol.readIntLE(dis);
                int channels = TwoyiHalProtocol.readIntLE(dis);
                int requestedLen = TwoyiHalProtocol.readIntLE(dis);

                switch (cmd) {
                    case TwoyiHalProtocol.CMD_MIC_START:
                    case TwoyiHalProtocol.CMD_MIC_READ_REQ:
                        mSampleRate = (sampleRate > 0) ? sampleRate : 44100;
                        mChannels = (channels > 0) ? channels : 1;

                        ensureAudioRecord(mSampleRate, mChannels);

                        int toRead = Math.min(requestedLen > 0 ? requestedLen : 2048, buffer.length);
                        int readBytes = 0;

                        if (mAudioRecord != null && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            readBytes = mAudioRecord.read(buffer, 0, toRead);
                        }

                        if (readBytes <= 0) {
                            // Silence if permission denied or buffer empty
                            readBytes = toRead;
                            for (int i = 0; i < readBytes; i++) buffer[i] = 0;
                        }

                        // Send response header
                        TwoyiHalProtocol.writeIntLE(dos, TwoyiHalProtocol.MIC_MAGIC);
                        TwoyiHalProtocol.writeIntLE(dos, TwoyiHalProtocol.CMD_MIC_DATA);
                        TwoyiHalProtocol.writeIntLE(dos, mSampleRate);
                        TwoyiHalProtocol.writeIntLE(dos, mChannels);
                        TwoyiHalProtocol.writeIntLE(dos, readBytes);
                        dos.write(buffer, 0, readBytes);
                        dos.flush();
                        break;

                    case TwoyiHalProtocol.CMD_MIC_STOP:
                        releaseAudioRecord();
                        break;

                    default:
                        break;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Mic client disconnected: " + e.getMessage());
        } finally {
            releaseAudioRecord();
            IOUtils.closeSilently(client);
        }
    }

    private synchronized void ensureAudioRecord(int sampleRate, int channels) {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted on host");
            return;
        }

        if (mAudioRecord != null) {
            if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                mAudioRecord.startRecording();
            }
            return;
        }

        int channelConfig = (channels == 2) ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBufSize * 2, 4096);

        try {
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                mAudioRecord.startRecording();
                Log.i(TAG, "AudioRecord started: " + sampleRate + "Hz, channels=" + channels);
            } else {
                Log.e(TAG, "Failed to initialize AudioRecord");
                releaseAudioRecord();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Error starting AudioRecord: " + e.getMessage());
            releaseAudioRecord();
        }
    }

    private synchronized void releaseAudioRecord() {
        if (mAudioRecord != null) {
            try {
                if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mAudioRecord.stop();
                }
                mAudioRecord.release();
            } catch (Throwable ignored) {}
            mAudioRecord = null;
        }
    }
}
