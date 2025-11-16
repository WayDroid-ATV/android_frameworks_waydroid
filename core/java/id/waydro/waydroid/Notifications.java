/**
 * Copyright (C) 2025 The WayDroid Project
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

import id.waydro.waydroid.INotifications;

import java.util.List;
import java.util.ArrayList;

public class Notifications {
    private static final String TAG = "WayDroidNotifications";

    private static INotifications sService;
    private static Notifications sInstance;

    private Context mContext;

    private Notifications(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();
        if (sService == null) {
            throw new RuntimeException("Unable to get WayDroidNotifications service. The service" +
                    " either crashed, was not started, or the interface has been called to early" +
                    " in SystemServer init");
        }
    }

    /**
     * Get or create an instance of the {@link id.waydro.waydroid.Notifications}
     *
     * @param context Used to get the service
     * @return {@link Notifications}
     */
    public static Notifications getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new Notifications(context);
        }
        return sInstance;
    }

    /** @hide **/
    public static INotifications getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(WaydroidContextConstants.WAYDROID_NOTIFICATIONS_SERVICE);

        if (b == null) {
            Log.e(TAG, "null service. SAD!");
            return null;
        }

        sService = INotifications.Stub.asInterface(b);
        return sService;
    }

    public int notify(int replaces_id, String app_name, String package_name, String summary, String body,
                      List<INotifications.Action> actions, INotifications.ImageData image, String category, boolean suppress_sound,
                      int expire_timeout, boolean is_resident, boolean is_transient, byte urgency) {
        if (sService == null) {
            return 0;
        }
        try {
            return sService.notify(replaces_id, app_name, package_name, summary, body,
                                   actions, image, category, suppress_sound,
                                   expire_timeout, is_resident, is_transient, urgency);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return 0;
    }

    public void closeNotification(int notification_id) {
        if (sService == null) {
            return;
        }
        try {
            sService.closeNotification(notification_id);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
    }

    public void registerListener(INotifications.INotificationCallback listener) {
        if (sService == null) {
            return;
        }
        try {
            sService.registerListener(listener);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
    }
}
