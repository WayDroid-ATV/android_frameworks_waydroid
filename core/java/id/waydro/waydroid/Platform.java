/**
 * Copyright (C) 2021 The WayDroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package id.waydro.waydroid;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import id.waydro.app.WaydroidContextConstants;

import java.util.List;
import java.util.ArrayList;

public class Platform {
    private static final String TAG = "WayDroidPlatform";

    /**
     * Unable to determine status, an error occured
     */
    public static final int ERROR_UNDEFINED = -1;

    /**
     * Settings providers
     */
    public static final int SETTINGS_SECURE = 0;
    public static final int SETTINGS_SYSTEM = 1;
    public static final int SETTINGS_GLOBAL = 2;

    private static IPlatform sService;
    private static Platform sInstance;

    private Context mContext;

    private Platform(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();
        if (sService == null) {
            throw new RuntimeException("Unable to get WayDroidService. The service" +
                    " either crashed, was not started, or the interface has been called to early" +
                    " in SystemServer init");
                }
    }

    /**
     * Get or create an instance of the {@link id.waydro.waydroid.Platform}
     *
     * @param context Used to get the service
     * @return {@link Platform}
     */
    public static Platform getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new Platform(context);
        }
        return sInstance;
    }

    /** @hide **/
    public static IPlatform getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(WaydroidContextConstants.WAYDROID_PLATFORM_SERVICE);

        if (b == null) {
            Log.e(TAG, "null service. SAD!");
            return null;
        }

        sService = IPlatform.Stub.asInterface(b);
        return sService;
    }

    /** @hide **/
    public String getprop(String prop, String default_value) {
        if (sService == null) {
            return default_value;
        }
        try {
            return sService.getprop(prop, default_value);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return default_value;
    }

    public void setprop(String prop, String value) {
        if (sService == null) {
            return;
        }
        try {
            sService.setprop(prop, value);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public List<AppInfo> getAppsInfo() {
        List<AppInfo> result = new ArrayList<>();
        if (sService == null) {
            return result;
        }
        try {
            result = sService.getAppsInfo();
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return result;
    }

    public AppInfo getAppInfo(String packageName) {
        if (sService == null) {
            return null;
        }
        try {
            return sService.getAppInfo(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return null;
    }

    public int installApp(String path) {
        if (sService == null) {
            return ERROR_UNDEFINED;
        }
        try {
            return sService.installApp(path);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return ERROR_UNDEFINED;
    }

    public int removeApp(String packageName) {
        if (sService == null) {
            return ERROR_UNDEFINED;
        }
        try {
            return sService.removeApp(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return ERROR_UNDEFINED;
    }

    public void launchApp(String packageName) {
        if (sService == null) {
            return;
        }
        try {
            sService.launchApp(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public String getAppName(String packageName) {
        if (sService == null) {
            return "";
        }
        try {
            return sService.getAppName(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return "";
    }

    public void settingsPutString(int mode, String key, String value) {
        if (sService == null) {
            return;
        }
        try {
            sService.settingsPutString(mode, key, value);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public String settingsGetString(int mode, String key) {
        if (sService == null) {
            return "";
        }
        try {
            return sService.settingsGetString(mode, key);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return "";
    }

    public void settingsPutInt(int mode, String key, int value) {
        if (sService == null) {
            return;
        }
        try {
            sService.settingsPutInt(mode, key, value);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public int settingsGetInt(int mode, String key) {
        if (sService == null) {
            return ERROR_UNDEFINED;
        }
        try {
            return sService.settingsGetInt(mode, key);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return ERROR_UNDEFINED;
    }
}
