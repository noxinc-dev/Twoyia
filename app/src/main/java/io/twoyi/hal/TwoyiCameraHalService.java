/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.hal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.IOUtils;

/**
 * Host-side Camera HAL Service.
 * Interfaces with host Camera2 API, captures camera frames from physical sensor,
 * and streams video frames to the Guest OS Camera HAL over a local UNIX domain socket.
 */
public class TwoyiCameraHalService {

    private static final String TAG = "TwoyiCameraHalService";

    private final Context mContext;
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private LocalServerSocket mServerSocket;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    private DataOutputStream mCurrentClientOut;
    private final Object mClientLock = new Object();

    public TwoyiCameraHalService(Context context) {
        mContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (mRunning.compareAndSet(false, true)) {
            mCameraThread = new HandlerThread("TwoyiCameraThread");
            mCameraThread.start();
            mCameraHandler = new Handler(mCameraThread.getLooper());

            mExecutor.submit(this::serverLoop);
            Log.i(TAG, "Twoyi Camera HAL Service started");
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
        stopCameraCapture();
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            mCameraThread = null;
        }
        Log.i(TAG, "Twoyi Camera HAL Service stopped");
    }

    private void serverLoop() {
        LocalSocket serverSocketRaw = null;
        try {
            serverSocketRaw = new LocalSocket(LocalSocket.SOCKET_STREAM);
            serverSocketRaw.bind(new LocalSocketAddress(TwoyiHalProtocol.SOCK_CAMERA, LocalSocketAddress.Namespace.ABSTRACT));
            mServerSocket = new LocalServerSocket(serverSocketRaw.getFileDescriptor());

            Log.i(TAG, "Camera HAL server listening on: " + TwoyiHalProtocol.SOCK_CAMERA);

            while (mRunning.get()) {
                LocalSocket client = mServerSocket.accept();
                Log.i(TAG, "Guest Camera HAL connected");
                mExecutor.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                Log.e(TAG, "Camera server loop error: " + e.getMessage());
            }
        } finally {
            IOUtils.closeSilently(serverSocketRaw);
        }
    }

    private void handleClient(LocalSocket client) {
        try (DataInputStream dis = new DataInputStream(client.getInputStream());
             DataOutputStream dos = new DataOutputStream(client.getOutputStream())) {

            synchronized (mClientLock) {
                mCurrentClientOut = dos;
            }

            while (mRunning.get()) {
                int magic = TwoyiHalProtocol.readIntLE(dis);
                if (magic != TwoyiHalProtocol.CAMERA_MAGIC) {
                    Log.w(TAG, "Invalid camera magic: 0x" + Integer.toHexString(magic));
                    break;
                }

                int cmd = TwoyiHalProtocol.readIntLE(dis);
                int facing = TwoyiHalProtocol.readIntLE(dis);
                int width = TwoyiHalProtocol.readIntLE(dis);
                int height = TwoyiHalProtocol.readIntLE(dis);
                int format = TwoyiHalProtocol.readIntLE(dis);
                long timestamp = 0;
                int b0 = dis.readUnsignedByte();
                int b1 = dis.readUnsignedByte();
                int b2 = dis.readUnsignedByte();
                int b3 = dis.readUnsignedByte();
                int b4 = dis.readUnsignedByte();
                int b5 = dis.readUnsignedByte();
                int b6 = dis.readUnsignedByte();
                int b7 = dis.readUnsignedByte();
                int payloadLen = TwoyiHalProtocol.readIntLE(dis);

                switch (cmd) {
                    case TwoyiHalProtocol.CMD_CAM_START_STREAM:
                        int targetWidth = (width > 0 && width <= 1920) ? width : 1280;
                        int targetHeight = (height > 0 && height <= 1080) ? height : 720;
                        startCameraCapture(facing, targetWidth, targetHeight);
                        break;

                    case TwoyiHalProtocol.CMD_CAM_STOP_STREAM:
                        stopCameraCapture();
                        break;

                    default:
                        if (payloadLen > 0) {
                            dis.skipBytes(payloadLen);
                        }
                        break;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Camera client disconnected: " + e.getMessage());
        } finally {
            synchronized (mClientLock) {
                mCurrentClientOut = null;
            }
            stopCameraCapture();
            IOUtils.closeSilently(client);
        }
    }

    private synchronized void startCameraCapture(int facing, int width, int height) {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA permission not granted on host device");
            return;
        }

        stopCameraCapture();

        CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) return;

        try {
            String selectedCameraId = null;
            int targetFacing = (facing == 1) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;

            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer cameraFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (cameraFacing != null && cameraFacing == targetFacing) {
                    selectedCameraId = id;
                    break;
                }
            }

            if (selectedCameraId == null && cameraManager.getCameraIdList().length > 0) {
                selectedCameraId = cameraManager.getCameraIdList()[0];
            }

            if (selectedCameraId == null) {
                Log.w(TAG, "No camera found on host");
                return;
            }

            mImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
            mImageReader.setOnImageAvailableListener(this::onImageAvailable, mCameraHandler);

            String finalId = selectedCameraId;
            cameraManager.openCamera(finalId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    mCameraDevice = null;
                    Log.e(TAG, "Camera open error: " + error);
                }
            }, mCameraHandler);

        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Failed to start camera capture: " + e.getMessage());
        }
    }

    private void createCaptureSession() {
        if (mCameraDevice == null || mImageReader == null) return;

        try {
            mCameraDevice.createCaptureSession(Collections.singletonList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (mCameraDevice == null) return;
                            mCaptureSession = session;
                            try {
                                CaptureRequest.Builder requestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                requestBuilder.addTarget(mImageReader.getSurface());
                                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                mCaptureSession.setRepeatingRequest(requestBuilder.build(), null, mCameraHandler);
                                Log.i(TAG, "Camera repeating capture session configured successfully");
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to start camera preview: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera capture session configuration failed");
                        }
                    }, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating camera capture session: " + e.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            synchronized (mClientLock) {
                if (mCurrentClientOut == null) return;

                int width = image.getWidth();
                int height = image.getHeight();
                long timestamp = image.getTimestamp();

                // Convert YUV_420_888 to contiguous YUV420 buffer
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer yBuffer = planes[0].getBuffer();
                ByteBuffer uBuffer = planes[1].getBuffer();
                ByteBuffer vBuffer = planes[2].getBuffer();

                int ySize = yBuffer.remaining();
                int uSize = uBuffer.remaining();
                int vSize = vBuffer.remaining();
                int totalSize = ySize + uSize + vSize;

                byte[] frameBytes = new byte[totalSize];
                yBuffer.get(frameBytes, 0, ySize);
                uBuffer.get(frameBytes, ySize, uSize);
                vBuffer.get(frameBytes, ySize + uSize, vSize);

                // Write packet header
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, TwoyiHalProtocol.CAMERA_MAGIC);
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, TwoyiHalProtocol.CMD_CAM_FRAME);
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, 0); // facing
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, width);
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, height);
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, TwoyiHalProtocol.CAM_FMT_YUV420);
                TwoyiHalProtocol.writeLongLE(mCurrentClientOut, timestamp);
                TwoyiHalProtocol.writeIntLE(mCurrentClientOut, totalSize);

                // Write frame payload
                mCurrentClientOut.write(frameBytes, 0, totalSize);
                mCurrentClientOut.flush();
            }
        } catch (Throwable t) {
            // Drop frame on write failure or socket close
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private synchronized void stopCameraCapture() {
        if (mCaptureSession != null) {
            try {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
            } catch (Throwable ignored) {}
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            try {
                mCameraDevice.close();
            } catch (Throwable ignored) {}
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            try {
                mImageReader.close();
            } catch (Throwable ignored) {}
            mImageReader = null;
        }
    }
}
