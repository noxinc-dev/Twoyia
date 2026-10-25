/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends AppCompatActivity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";

    private SurfaceView mSurfaceView;

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;

    private View mRomMissingLayout;
    private Button mBtnSelectRom;
    private Button mBtnSelectRomIni;
    private TextView mRomStatusText;
    private Button mBtnExitApp;

    private final AtomicBoolean mIsExtracting = new AtomicBoolean(false);

    private final ActivityResultLauncher<Intent> mSelectRomLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        installRomFromUri(uri);
                        return;
                    }
                }
                if (mRomMissingLayout != null && mRomMissingLayout.getVisibility() == View.VISIBLE) {
                    updateRomMissingStatus();
                    return;
                }
                showBootTimeoutUi();
            });

    private final ActivityResultLauncher<Intent> mSelectRomIniLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        saveRomIniFromUri(uri);
                        return;
                    }
                }
                updateRomMissingStatus();
            });

    private final ActivityResultLauncher<String[]> mHalPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Log.i(TAG, "HAL permission request results: " + result);
                boolean hasDenied = false;
                for (String permission : result.keySet()) {
                    Boolean granted = result.get(permission);
                    if (granted == null || !granted) {
                        hasDenied = true;
                        Log.w(TAG, "Permission denied: " + permission);
                    }
                }

                // If critical permissions like RECORD_AUDIO or CAMERA were denied and user checked "Don't ask again", show hint to settings
                if (hasDenied) {
                    boolean micPermanentlyDenied = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                            && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO);
                    boolean camPermanentlyDenied = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                            && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA);

                    if (micPermanentlyDenied || camPermanentlyDenied) {
                        showSettingsRationaleDialog();
                    }
                }
            });

    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            Surface surface = holder.getSurface();
            float xdpi;
            float ydpi;

            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            xdpi = displayMetrics.xdpi;
            ydpi = displayMetrics.ydpi;

            if (xdpi <= 0) xdpi = displayMetrics.densityDpi;
            if (ydpi <= 0) ydpi = displayMetrics.densityDpi;

            Renderer.init(surface, RomManager.getLoaderPath(getApplicationContext()), xdpi, ydpi, (int) getBestFps());

            Log.i(TAG, "surfaceCreated with xdpi=" + xdpi + ", ydpi=" + ydpi);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            Surface surface = holder.getSurface();
            Renderer.resetWindow(surface, 0, 0, mSurfaceView.getWidth(), mSurfaceView.getHeight());
            Log.i(TAG, "surfaceChanged: " + mSurfaceView.getWidth() + "x" + mSurfaceView.getHeight());
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            Renderer.removeWindow(holder.getSurface());
            Log.i(TAG, "surfaceDestroyed!");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean started = TwoyiStatusManager.getInstance().isStarted();
        Log.i(TAG, "onCreate: " + savedInstanceState + " isStarted: " + started);

        if (started) {
            // we have been started, but WTF we are onCreate again? just reboot ourself.
            finish();
            RomManager.reboot(this);
            return;
        }

        // reset state
        TwoyiStatusManager.getInstance().reset();

        NavUtils.hideNavigation(getWindow());

        super.onCreate(savedInstanceState);

        // Modern OnBackPressed handling (Android 13+ / API 33+)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
            }
        });

        requestHalPermissionsIfNecessary();

        setContentView(R.layout.ac_render);
        mRootView = findViewById(R.id.root);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);
        mBootLogView = findViewById(R.id.bootlog);

        mRomMissingLayout = findViewById(R.id.romMissingLayout);
        mBtnSelectRom = findViewById(R.id.btnSelectRom);
        mBtnSelectRomIni = findViewById(R.id.btnSelectRomIni);
        mRomStatusText = findViewById(R.id.romStatusText);
        mBtnExitApp = findViewById(R.id.btnExitApp);

        if (mBtnSelectRom != null) {
            mBtnSelectRom.setOnClickListener(v -> launchRomPicker());
        }
        if (mBtnSelectRomIni != null) {
            mBtnSelectRomIni.setOnClickListener(v -> launchRomIniPicker());
        }
        if (mBtnExitApp != null) {
            mBtnExitApp.setOnClickListener(v -> finish());
        }

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        UITips.checkForAndroid12(this, this::bootSystem);

        mSurfaceView.setOnTouchListener(this);
    }

    private void requestHalPermissionsIfNecessary() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            boolean shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                    || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA);

            if (shouldShowRationale) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_dialog_title)
                        .setMessage(R.string.permission_dialog_message)
                        .setPositiveButton(R.string.permission_grant, (dialog, which) -> {
                            mHalPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
                        })
                        .setNegativeButton(R.string.permission_cancel, null)
                        .show();
            } else {
                mHalPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
            }
        }
    }

    private void showSettingsRationaleDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_settings_title)
                .setMessage(R.string.permission_settings_message)
                .setPositiveButton(R.string.permission_go_settings, (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to open app settings: " + t.getMessage());
                    }
                })
                .setNegativeButton(R.string.permission_cancel, null)
                .show();
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState: " + savedInstanceState);

        // we don't support state restore, just reboot.
        finish();
        RomManager.reboot(this);
    }

    private void bootSystem() {
        boolean romExist = RomManager.romExist(this);
        boolean hasAssetRom = RomManager.hasAssetRom(this);
        boolean hasRomIni = RomManager.hasRomIni(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        if ((!romExist || (forceInstall && !use3rdRom)) && !hasAssetRom) {
            Log.i(TAG, "No ROM installed and no ROM in assets. Prompting user to select ROM from storage.");
            runOnUiThread(this::showRomMissingDialog);
            return;
        }

        if (romExist && !hasRomIni) {
            Log.i(TAG, "ROM installed but ROM.ini missing. Prompting user to select ROM.ini or continue.");
            runOnUiThread(this::showRomIniMissingDialog);
            return;
        }

        proceedWithBoot();
    }

    private void proceedWithBoot() {
        boolean romExist = RomManager.romExist(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        if (shouldExtractRom) {
            Log.i(TAG, "extracting rom...");

            showTipsForFirstBoot();

            new Thread(() -> {
                mIsExtracting.set(true);
                RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                mIsExtracting.set(false);

                RomManager.initRootfs(getApplicationContext());

                runOnUiThread(() -> {
                    mRootView.addView(mSurfaceView, 0);
                    showBootingProcedure();
                });
            }, "extract-rom").start();
        } else {
            mRootView.addView(mSurfaceView, 0);
            showBootingProcedure();
        }
    }

    private void launchRomPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-7z-compressed",
                "application/x-compressed",
                "application/octet-stream",
                "application/zip",
                "*/*"
        });
        try {
            mSelectRomLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                mSelectRomLauncher.launch(fallback);
            } catch (Throwable t) {
                Toast.makeText(this, R.string.rom_copy_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void launchRomIniPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain",
                "text/*",
                "application/octet-stream",
                "*/*"
        });
        try {
            mSelectRomIniLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                mSelectRomIniLauncher.launch(fallback);
            } catch (Throwable t) {
                Toast.makeText(this, R.string.rom_ini_read_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveRomIniFromUri(Uri uri) {
        new Thread(() -> {
            boolean success = false;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    success = RomManager.saveRomIni(getApplicationContext(), is);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to read ROM.ini from URI: " + uri, t);
            }

            final boolean saved = success;
            runOnUiThread(() -> {
                if (saved) {
                    Toast.makeText(Render2Activity.this, R.string.rom_ini_installed_success, Toast.LENGTH_SHORT).show();
                    updateRomMissingStatus();
                    if (RomManager.romExist(Render2Activity.this)) {
                        proceedWithBoot();
                    } else {
                        showRomMissingUi();
                    }
                } else {
                    Toast.makeText(Render2Activity.this, R.string.rom_ini_read_failed, Toast.LENGTH_LONG).show();
                    updateRomMissingStatus();
                }
            });
        }, "save-rom-ini").start();
    }

    private void showRomIniMissingDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.rom_ini_not_found_title)
                .setMessage(R.string.rom_ini_not_found_message)
                .setCancelable(false)
                .setPositiveButton(R.string.btn_select_rom_ini, (dialog, which) -> {
                    dialog.dismiss();
                    launchRomIniPicker();
                })
                .setNeutralButton(R.string.btn_continue_default, (dialog, which) -> {
                    dialog.dismiss();
                    RomManager.createDefaultRomIniIfMissing(getApplicationContext());
                    proceedWithBoot();
                })
                .setNegativeButton(R.string.btn_exit, (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                    System.exit(0);
                })
                .show();
    }

    private void updateRomMissingStatus() {
        if (mRomStatusText != null) {
            boolean hasRom = RomManager.romExist(this) || RomManager.hasAssetRom(this);
            boolean hasIni = RomManager.hasRomIni(this);
            String romStatus = hasRom ? getString(R.string.status_present) : getString(R.string.status_missing);
            String iniStatus = hasIni ? getString(R.string.status_present) : getString(R.string.status_missing);
            mRomStatusText.setText(getString(R.string.rom_status_info, romStatus, iniStatus));
            mRomStatusText.setVisibility(View.VISIBLE);
        }
    }

    private void showRomMissingDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        showRomMissingUi();
        new AlertDialog.Builder(this)
                .setTitle(R.string.rom_not_found_title)
                .setMessage(R.string.rom_not_found_message)
                .setCancelable(false)
                .setPositiveButton(R.string.btn_select_rom, (dialog, which) -> {
                    dialog.dismiss();
                    launchRomPicker();
                })
                .setNeutralButton(R.string.btn_select_rom_ini, (dialog, which) -> {
                    dialog.dismiss();
                    launchRomIniPicker();
                })
                .setNegativeButton(R.string.btn_exit, (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .show();
    }

    private void showRomMissingUi() {
        if (mLoadingView != null) {
            mLoadingView.stopAnimation();
        }
        if (mLoadingLayout != null) {
            mLoadingLayout.setVisibility(View.GONE);
        }
        if (mRomMissingLayout != null) {
            TextView titleView = findViewById(R.id.romMissingTitle);
            TextView msgView = findViewById(R.id.romMissingMessage);
            if (titleView != null) {
                titleView.setText(R.string.rom_not_found_title);
            }
            if (msgView != null) {
                msgView.setText(R.string.rom_not_found_message);
            }
            if (mBtnSelectRom != null) {
                mBtnSelectRom.setText(R.string.btn_select_rom);
            }
            if (mBtnSelectRomIni != null) {
                mBtnSelectRomIni.setVisibility(View.VISIBLE);
            }
            updateRomMissingStatus();
            mRomMissingLayout.setVisibility(View.VISIBLE);
        }
    }

    private void showBootTimeoutUi() {
        if (mLoadingView != null) {
            mLoadingView.stopAnimation();
        }
        if (mLoadingLayout != null) {
            mLoadingLayout.setVisibility(View.GONE);
        }
        if (mRomMissingLayout != null) {
            TextView titleView = findViewById(R.id.romMissingTitle);
            TextView msgView = findViewById(R.id.romMissingMessage);
            if (titleView != null) {
                titleView.setText(R.string.boot_timeout_dialog_title);
            }
            if (msgView != null) {
                msgView.setText(R.string.boot_timeout_dialog_message);
            }
            if (mBtnSelectRom != null) {
                mBtnSelectRom.setText(R.string.btn_select_another_rom);
            }
            if (mBtnSelectRomIni != null) {
                mBtnSelectRomIni.setVisibility(View.VISIBLE);
            }
            updateRomMissingStatus();
            mRomMissingLayout.setVisibility(View.VISIBLE);
        }
    }

    private void showBootTimeoutDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }

        showBootTimeoutUi();

        new AlertDialog.Builder(this)
                .setTitle(R.string.boot_timeout_dialog_title)
                .setMessage(R.string.boot_timeout_dialog_message)
                .setCancelable(false)
                .setPositiveButton(R.string.btn_select_another_rom, (dialog, which) -> {
                    dialog.dismiss();
                    launchRomPicker();
                })
                .setNeutralButton(R.string.btn_select_rom_ini, (dialog, which) -> {
                    dialog.dismiss();
                    launchRomIniPicker();
                })
                .setNegativeButton(R.string.btn_retry_boot, (dialog, which) -> {
                    dialog.dismiss();
                    RomManager.reboot(Render2Activity.this);
                })
                .show();
    }

    private void installRomFromUri(Uri uri) {
        if (mRomMissingLayout != null) {
            mRomMissingLayout.setVisibility(View.GONE);
        }
        if (mLoadingLayout != null) {
            mLoadingLayout.setVisibility(View.VISIBLE);
        }
        if (mLoadingView != null) {
            mLoadingView.startAnimation();
        }
        if (mLoadingText != null) {
            mLoadingText.setVisibility(View.VISIBLE);
            mLoadingText.setText(R.string.copying_rom);
        }
        if (mBootLogView != null) {
            mBootLogView.setVisibility(View.GONE);
        }

        new Thread(() -> {
            File targetRom = getApplicationContext().getFileStreamPath(RomManager.ROOTFS_NAME);
            boolean copySuccess = false;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(targetRom))) {
                if (is != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                    os.flush();
                    copySuccess = targetRom.exists() && targetRom.length() > 0;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to copy ROM from URI: " + uri, t);
            }

            if (!copySuccess) {
                runOnUiThread(() -> {
                    Toast.makeText(Render2Activity.this, R.string.rom_copy_failed, Toast.LENGTH_LONG).show();
                    showBootTimeoutUi();
                });
                return;
            }

            runOnUiThread(() -> {
                if (mLoadingText != null) {
                    mLoadingText.setText(R.string.verifying_rom);
                }
            });

            boolean isValid = RomManager.isValidRomArchive(targetRom);
            if (!isValid) {
                targetRom.delete();
                runOnUiThread(() -> {
                    Toast.makeText(Render2Activity.this, R.string.replace_rom_invalid, Toast.LENGTH_LONG).show();
                    showBootTimeoutUi();
                });
                return;
            }

            runOnUiThread(() -> {
                if (mLoadingText != null) {
                    mLoadingText.setText(R.string.extracting_tips);
                }
                showTipsForFirstBoot();
            });

            mIsExtracting.set(true);
            boolean installed = RomManager.installRomFromFile(getApplicationContext(), targetRom);
            mIsExtracting.set(false);

            if (!installed) {
                targetRom.delete();
                runOnUiThread(() -> {
                    Toast.makeText(Render2Activity.this, R.string.rom_extract_failed, Toast.LENGTH_LONG).show();
                    showBootTimeoutUi();
                });
                return;
            }

            boolean hasRomIni = RomManager.hasRomIni(getApplicationContext());
            if (!hasRomIni) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(Render2Activity.this)
                            .setTitle(R.string.rom_ini_not_found_title)
                            .setMessage(R.string.rom_ini_select_prompt)
                            .setCancelable(false)
                            .setPositiveButton(R.string.btn_select_rom_ini, (dialog, which) -> {
                                dialog.dismiss();
                                launchRomIniPicker();
                            })
                            .setNeutralButton(R.string.btn_continue_default, (dialog, which) -> {
                                dialog.dismiss();
                                RomManager.createDefaultRomIniIfMissing(getApplicationContext());
                                finishRomInstallAndReboot();
                            })
                            .show();
                });
            } else {
                runOnUiThread(this::finishRomInstallAndReboot);
            }
        }, "install-rom-thread").start();
    }

    private void finishRomInstallAndReboot() {
        Toast.makeText(Render2Activity.this, R.string.rom_install_success_rebooting, Toast.LENGTH_SHORT).show();
        if (mSurfaceView != null && mSurfaceView.getParent() != null) {
            mRootView.postDelayed(() -> RomManager.reboot(Render2Activity.this), 1200);
        } else {
            if (mSurfaceView != null && mSurfaceView.getParent() == null) {
                mRootView.addView(mSurfaceView, 0);
            }
            showBootingProcedure();
        }
    }

    private void showTipsForFirstBoot() {
        mLoadingText.setText(R.string.extracting_tips);
        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips);
            }
        }, 5000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips2);
            }
        }, 10 * 1000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips3);
            }
        }, 15 * 1000);
    }

    private void showBootingProcedure() {
        // mLoadingText.setText(R.string.booting_tips);
        mLoadingText.setVisibility(View.GONE);
        mBootLogView.setVisibility(View.VISIBLE);
        new Thread(() -> {

            if (true) {
                boolean success = false;
                try {
                    success = TwoyiStatusManager.getInstance().waitBoot(5, TimeUnit.MINUTES);
                } catch (Throwable ignored) {
                }

                if (!success) {
                    runOnUiThread(this::showBootTimeoutDialog);
                    return;
                }
            }

            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
            });
        }, "waiting-boot").start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
        }

        // Update global visibility.
        TwoyiStatusManager.getInstance().updateVisibility(hasFocus);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Renderer.handleTouch(event);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // TODO: 2021/10/26 Add Volume control
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // super.onBackPressed();
        Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
    }

    private float getBestFps() {
        WindowManager windowManager = getWindowManager();
        Display defaultDisplay = windowManager.getDefaultDisplay();
        Display.Mode[] supportedModes = defaultDisplay.getSupportedModes();
        float fps = 45;
        for (Display.Mode supportedMode : supportedModes) {
            float refreshRate = supportedMode.getRefreshRate();
            if (refreshRate > fps) {
                // fps = refreshRate;
            }
        }

        Log.w(TAG, "current fps: " + fps);
        return fps;
    }
}
