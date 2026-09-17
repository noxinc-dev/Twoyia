/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.hal;

import android.content.Context;
import android.util.Log;

/**
 * Central manager for all Twoyi Hardware Abstraction Layer (HAL) host services.
 * Coordinates Audio, Microphone, and Camera virtualization.
 */
public class TwoyiHalManager {

    private static final String TAG = "TwoyiHalManager";
    private static volatile TwoyiHalManager sInstance;

    private TwoyiAudioHalService mAudioService;
    private TwoyiMicHalService mMicService;
    private TwoyiCameraHalService mCameraService;
    private boolean mInitialized = false;

    private TwoyiHalManager() {}

    public static TwoyiHalManager getInstance() {
        if (sInstance == null) {
            synchronized (TwoyiHalManager.class) {
                if (sInstance == null) {
                    sInstance = new TwoyiHalManager();
                }
            }
        }
        return sInstance;
    }

    /**
     * Initializes and starts all HAL services.
     */
    public synchronized void init(Context context) {
        if (mInitialized || context == null) return;

        Context appCtx = context.getApplicationContext();
        if (appCtx == null) {
            appCtx = context;
        }

        mAudioService = new TwoyiAudioHalService(appCtx);
        mMicService = new TwoyiMicHalService(appCtx);
        mCameraService = new TwoyiCameraHalService(appCtx);

        mAudioService.start();
        mMicService.start();
        mCameraService.start();

        mInitialized = true;
        Log.i(TAG, "Twoyi HAL Subsystem initialized (Audio, Mic, Camera services running)");
    }

    public synchronized void shutdown() {
        if (!mInitialized) return;

        if (mAudioService != null) mAudioService.stop();
        if (mMicService != null) mMicService.stop();
        if (mCameraService != null) mCameraService.stop();

        mInitialized = false;
        Log.i(TAG, "Twoyi HAL Subsystem shut down");
    }

    public TwoyiAudioHalService getAudioService() {
        return mAudioService;
    }

    public TwoyiMicHalService getMicService() {
        return mMicService;
    }

    public TwoyiCameraHalService getCameraService() {
        return mCameraService;
    }

    public boolean isInitialized() {
        return mInitialized;
    }
}
