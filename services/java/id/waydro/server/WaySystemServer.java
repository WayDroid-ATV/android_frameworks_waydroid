/**
 * Copyright (C) 2025 The WayDroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package id.waydro.server;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Slog;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;

/**
 * Base WayDroid System Server which handles the starting and states of various WayDroid
 * specific system services. Since its part of the main looper provided by the system
 * server, it will be available indefinitely (until all the things die).
 */
public class WaySystemServer {
    private static final String TAG = "WaySystemServer";

    public static void startServices(Context context, SystemServiceManager ssm) {
        String[] externalServices = context.getResources().getStringArray(
                com.android.internal.R.array.config_externalWayDroidServices);

        for (String service : externalServices) {
            try {
                Slog.i(TAG, "Starting service " + service);
                ssm.startService(service);
            } catch (Throwable e) {
                reportWtf("starting " + service , e);
            }
        }
    }

    private static void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }
}
