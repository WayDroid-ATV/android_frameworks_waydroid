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

interface IHardware {
    int enableNFC(boolean enable);
    int enableBluetooth(boolean enable);
    void suspend();
    void reboot();
    void upgrade(String system_zip, int system_time, String vendor_zip, int vendor_time);
    void upgrade2(String system_zip, long system_time, String vendor_zip, long vendor_time);
    oneway void shutdownRequest(String reason);
}
