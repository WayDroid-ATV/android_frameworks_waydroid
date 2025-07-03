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

import java.util.NoSuchElementException;

import id.waydro.app.WaydroidContextConstants;
import vendor.waydroid.clipboard.V1_0.IWaydroidClipboard;

public class Clipboard {
    private static final String TAG = "WayDroidClipboard";

    private static IWaydroidClipboard sService;
    private static Clipboard sInstance;

    private Context mContext;

    private Clipboard(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();
    }

    /**
     * Get or create an instance of the {@link id.waydro.waydroid.Clipboard}
     *
     * @param context Used to get the service
     * @return {@link Clipboard}
     */
    public static Clipboard getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new Clipboard(context);
        }
        return sInstance;
    }

    /** @hide **/
    public static IWaydroidClipboard getService() {
        if (sService != null) {
            return sService;
        }
        try {
            sService = IWaydroidClipboard.getService(false /* retry */);
        } catch (RemoteException | NoSuchElementException e) {
            Log.e(TAG, "null service. SAD!");
        }
        return sService;
    }

    /** @hide **/
    public void sendClipboardData(String value) {
        IWaydroidClipboard service = getService();
        if (service == null) {
            return;
        }
        try {
            service.sendClipboardData(value);
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return;
    }

    public String getClipboardData() {
        IWaydroidClipboard service = getService();
        if (service == null) {
            return "";
        }
        try {
            String paste = service.getClipboardData();
            return paste != null ? paste : "";
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return "";
    }

}
