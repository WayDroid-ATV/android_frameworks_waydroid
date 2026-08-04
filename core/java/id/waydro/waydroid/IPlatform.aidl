/*
**
** Copyright (C) 2021 The WayDroid Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package id.waydro.waydroid;

import id.waydro.waydroid.AppInfo;

interface IPlatform {
    String getprop(String prop, String default_value);
    void setprop(String prop, String value);

    List<AppInfo> getAppsInfo();
    AppInfo getAppInfo(String packageName);
    int installApp(String path);
    int removeApp(String packageName);
    void launchApp(String packageName);
    String getAppName(String packageName);

    void settingsPutString(int mode, String key, String value);
    String settingsGetString(int mode, String key);
    void settingsPutInt(int mode, String key, int value);
    int settingsGetInt(int mode, String key);
    String launchIntent(String action, String uri);

    /* Append only: the host keys off transaction numbers, which follow
     * declaration order here. */

    /* Show an app in the host shell: one call for the window mode and the
     * launch, so the two cannot disagree. False means nothing launchable. */
    boolean showApp(String packageName);
    void showFullUI();
}
