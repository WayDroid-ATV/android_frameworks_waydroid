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

public class UserMonitor {
    private static final String TAG = "WayDroidUserMonitor";

    public static final int WAYDROID_PACKAGE_ADDED = 0;
    public static final int WAYDROID_PACKAGE_REMOVED = 1;
    public static final int WAYDROID_PACKAGE_UPDATED = 2;

    private static IUserMonitor sService;
    private static UserMonitor sInstance;

    private Context mContext;

    private UserMonitor(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();
    }

    /**
     * Get or create an instance of the {@link id.waydro.waydroid.UserMonitor}
     *
     * @param context Used to get the service
     * @return {@link UserMonitor}
     */
    public static UserMonitor getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UserMonitor(context);
        }
        return sInstance;
    }

    /** @hide **/
    public static IUserMonitor getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(WaydroidContextConstants.WAYDROID_USERMONITOR_SERVICE);

        if (b == null) {
            Log.e(TAG, "null service. SAD!");
            return null;
        }

        sService = IUserMonitor.Stub.asInterface(b);
        return sService;
    }

    /** @hide **/
    public void userUnlocked(int uid) {
        IUserMonitor service = getService();
        if (service == null) {
            return;
        }
        try {
            service.userUnlocked(uid);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public void packageStateChanged(int mode, String packageName, int uid) {
        IUserMonitor service = getService();
        if (service == null) {
            return;
        }
        try {
            service.packageStateChanged(mode, packageName, uid);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }
}
