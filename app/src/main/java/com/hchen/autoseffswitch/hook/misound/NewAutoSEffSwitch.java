/*
 * This file is part of AutoSEffSwitch.

 * AutoSEffSwitch is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2024 AutoSEffSwitch Contributions
 */
package com.hchen.autoseffswitch.hook.misound;

import static com.hchen.hooktool.log.XposedLog.logI;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import com.hchen.autoseffswitch.hook.misound.callback.IControl;
import com.hchen.autoseffswitch.hook.misound.control.AudioEffectControl;
import com.hchen.autoseffswitch.hook.misound.control.FWAudioEffectControl;
import com.hchen.autoseffswitch.hook.misound.helper.BluetoothClassHelper;
import com.hchen.hooktool.BaseHC;
import com.hchen.hooktool.tool.additional.SystemPropTool;

import org.luckypray.dexkit.DexKitBridge;

/**
 * 新版连接耳机自动切换原声
 *
 * @author 焕晨HChen
 */
public class NewAutoSEffSwitch extends BaseHC {
    public static final String TAG = "AutoSEffSwitch";
    private Context mContext;
    public static DexKitBridge mDexKit;
    public static boolean isEarPhoneConnection = false;
    private static boolean isBroadcastReceiverCanUse = false;
    private static AudioManager mAudioManager;
    private DumpHandler mDumpHandler;
    private FWAudioEffectControl mFWAudioEffectControl = null;
    private AudioEffectControl mAudioEffectControl = null;
    private static final int DUMP = 0;
    private IControl mControl;

    @Override
    public void init() {
        if (isSupportFW()) {
            mFWAudioEffectControl = new FWAudioEffectControl();
            mFWAudioEffectControl.init();
            mControl = mFWAudioEffectControl;
        } else {
            mAudioEffectControl = new AudioEffectControl();
            mAudioEffectControl.init();
            mControl = mAudioEffectControl;
        }
    }

    public static boolean isSupportFW() {
        // 部分机型 OS2 没有对应 Prop 但是使用 AudioEffectCenter
        return SystemPropTool.getProp("ro.vendor.audio.fweffect", false) || existsClass("android.media.audiofx.AudioEffectCenter");
    }

    @Override
    protected void onApplicationAfter(Context context) {
        mContext = context;
        if (mFWAudioEffectControl != null && mFWAudioEffectControl.mAudioEffectCenter != null) {
            mFWAudioEffectControl.mAudioEffectCenterInstance = callStaticMethod(mFWAudioEffectControl.mAudioEffectCenter, "getInstance", mContext);
            logI(TAG, "mAudioEffectCenterInstance: " + mFWAudioEffectControl.mAudioEffectCenterInstance);
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(AudioManager.ACTION_HEADSET_PLUG);
        mContext.registerReceiver(new AudioManagerListener(), intentFilter);
        mDumpHandler = new DumpHandler(mContext.getMainLooper());

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        updateEarPhoneState();
    }

    public static void updateEarPhoneState() {
        if (mAudioManager == null) return;
        if (isBroadcastReceiverCanUse) {
            logI(TAG, "updateEarPhoneState: isEarPhoneConnection: " + isEarPhoneConnection);
            return;
        }

        isEarPhoneConnection = getEarPhoneState();
    }

    public static boolean getEarPhoneState() {
        if (isEarPhoneConnection) return true;

        AudioDeviceInfo[] outputs = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo info : outputs) {
            if (info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || info.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                logI(TAG, "updateEarPhoneState: isEarPhoneConnection: true.");
                return true;
            }
        }

        logI(TAG, "updateEarPhoneState: isEarPhoneConnection: false.");
        return false;
    }

    private class AudioManagerListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            isBroadcastReceiverCanUse = true;
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (!checkIsTargetBluetooth(intent))
                            return;

                        logI(TAG, "ACTION_ACL_CONNECTED!");
                        isEarPhoneConnection = true;
                        mControl.updateLastEffectState();
                        mControl.setEffectToNone(mContext);
                        dump();
                    }
                    case BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (!isEarPhoneConnection) return; // 没连接过关什么？

                        logI(TAG, "ACTION_ACL_DISCONNECTED!");
                        isEarPhoneConnection = false;
                        mControl.resetAudioEffect();
                        dump();
                    }
                    case AudioManager.ACTION_HEADSET_PLUG -> {
                        if (intent.hasExtra("state")) {
                            int state = intent.getIntExtra("state", 0);
                            if (state == 1) {
                                if (!checkIsTargetBluetooth(intent))
                                    return;

                                logI(TAG, "ACTION_HEADSET_PLUG CONNECTED! ");
                                isEarPhoneConnection = true;
                                mControl.updateLastEffectState();
                                mControl.setEffectToNone(mContext);
                                dump();
                            } else if (state == 0) {
                                if (!isEarPhoneConnection) return; // 没连接过关什么？

                                logI(TAG, "ACTION_HEADSET_PLUG DISCONNECTED!");
                                isEarPhoneConnection = false;
                                mControl.resetAudioEffect();
                                mControl.dumpAudioEffectState();
                                dump();
                            }
                        }
                    }
                }
            }
        }

        private boolean checkIsTargetBluetooth(Intent intent) {
            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            logI(TAG, "checkIsTargetBluetooth: extra: " + device);
            if (device == null) {
                // 检查有线耳机
                AudioDeviceInfo[] outputs = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo info : outputs) {
                    if (info.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                        logI(TAG, "checkIsTargetBluetooth: wired headset: true.");
                        return true;
                    }
                }
                return false;
            }

            @SuppressLint("MissingPermission")
            BluetoothClass bluetoothClass = device.getBluetoothClass();
            if (bluetoothClass == null) return false;

            boolean result = BluetoothClassHelper.doesClassMatch(bluetoothClass, BluetoothClassHelper.PROFILE_A2DP) ||
                    BluetoothClassHelper.doesClassMatch(bluetoothClass, BluetoothClassHelper.PROFILE_HEADSET);
            logI(TAG, "checkIsTargetBluetooth: " + result);
            return result;
        }

        private void dump() {
            if (mDumpHandler.hasMessages(DUMP))
                mDumpHandler.removeMessages(0);
            mDumpHandler.sendEmptyMessageDelayed(DUMP, 1000);
        }
    }

    private class DumpHandler extends Handler {
        public DumpHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            int what = msg.what;

            if (what == DUMP) {
                mControl.dumpAudioEffectState();
            }
        }
    }
}
