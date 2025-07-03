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

public class Hardware {
    private static final String TAG = "WayDroidHardware";

    /**
     * Unable to determine status, an error occured
     */
    public static final int ERROR_UNDEFINED = -1;

    private static IHardware sService;
    private static Hardware sInstance;

    private Context mContext;

    private Hardware(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();
    }

    /**
     * Get or create an instance of the {@link id.waydro.waydroid.Hardware}
     *
     * @param context Used to get the service
     * @return {@link Hardware}
     */
    public static Hardware getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new Hardware(context);
        }
        return sInstance;
    }

    /** @hide **/
    public static IHardware getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(WaydroidContextConstants.WAYDROID_HARDWARE_SERVICE);

        if (b == null) {
            Log.e(TAG, "null service. SAD!");
            return null;
        }

        sService = IHardware.Stub.asInterface(b);
        return sService;
    }

    /** @hide **/
    public int enableNFC(boolean enable) {
        IHardware service = getService();
        if (service == null) {
            return ERROR_UNDEFINED;
        }
        try {
            return service.enableNFC(enable);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return ERROR_UNDEFINED;
    }

    public int enableBluetooth(boolean enable) {
        IHardware service = getService();
        if (service == null) {
            return ERROR_UNDEFINED;
        }
        try {
            return service.enableBluetooth(enable);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return ERROR_UNDEFINED;
    }

    public void suspend() {
        IHardware service = getService();
        if (service == null) {
            return;
        }
        try {
            service.suspend();
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public void reboot() {
        IHardware service = getService();
        if (service == null) {
            return;
        }
        try {
            service.reboot();
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public void upgrade(String system_zip, long system_time, String vendor_zip, long vendor_time) {
        IHardware service = getService();
        if (service == null) {
            return;
        }
        try {
            service.upgrade2(system_zip, system_time, vendor_zip, vendor_time);
            // HACK: if we were not killed yet, the call was not implemented on the host side.
            // Fallback to the previous version
            Log.d(TAG, "IHardware.upgrade2 not implemented (detected through timeout), falling back to IHardware.upgrade");
            try {
                service.upgrade(system_zip, (int)system_time, vendor_zip, (int)vendor_time);
            } catch (RemoteException e) {
                Log.e(TAG, e.getLocalizedMessage(), e);
            }
        } catch (RemoteException | RuntimeException ignored) {
            Log.d(TAG, "IHardware.upgrade2 not implemented, falling back to IHardware.upgrade");
            try {
                service.upgrade(system_zip, (int)system_time, vendor_zip, (int)vendor_time);
            } catch (RemoteException e) {
                Log.e(TAG, e.getLocalizedMessage(), e);
            }
        }
        return;
    }
}
