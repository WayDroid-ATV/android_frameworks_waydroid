/*
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

package id.waydro.server;

import android.app.ActivityTaskManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.annotation.NonNull;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.net.Uri;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.hardware.HardwareBuffer;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.window.TaskSnapshot;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.R;

import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import vendor.waydroid.window.V1_3.IWaydroidWindow;

import com.android.server.SystemService;

import id.waydro.app.WaydroidContextConstants;
import id.waydro.waydroid.AppInfo;
import id.waydro.waydroid.IHardware;
import id.waydro.waydroid.Hardware;
import id.waydro.waydroid.IPlatform;
import id.waydro.waydroid.Platform;
import id.waydro.waydroid.IUserMonitor;
import id.waydro.waydroid.UserMonitor;
import id.waydro.waydroid.INotifications;
import id.waydro.waydroid.Notifications;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import libcore.io.IoUtils;

/** @hide **/
public class WayDroidService extends SystemService {
    private static final String TAG = "WayDroidService";
    private static final String BROADCAST_ACTION_INSTALL =
            "id.waydro.waydroid.ACTION_INSTALL_COMMIT";
    private static final String BROADCAST_ACTION_UNINSTALL =
            "id.waydro.waydroid.ACTION_UNINSTALL_COMMIT";
    private static final String ICONS_DIR = "/data/icons";

    private static final String WAYDROID_CHANNEL_ID = "WaydroidService";
    private static final String WAYDROID_CHANNEL_ID_TV = "WaydroidService.tv";

    private static final int INTEGRATION_WARN_ID = 1;
    private static final int DMABUF_WARN_ID = 1 << 2;
    private static final int SW_RENDERING_WARN_ID = 1 << 3;

    private Context mContext;
    private PackageManager mPm = null;
    private UserMonitor mUM = null;
    private Hardware mWaydroidHardware = null;
    private Notifications mWaydroidNotifications = null;
    private NotificationManager mNotificationManager = null;
    private NotificationListenerService mSystemNotificationListener = null;

    // Map android notification id -> host notification id
    private Map<String, Integer> mNotificationIdMap = new HashMap<String, Integer>();

    // Map (host notification id, host action id) -> PendingIntent
    private Map<Integer, Map<String, PendingIntent>> mNotificationActionsMap = new HashMap<Integer, Map<String, PendingIntent>>();

    public WayDroidService(Context context) {
        super(context);
        mContext = context;
        if (context != null) {
            mPm = context.getPackageManager();
        } else {
            Log.w(TAG, "No context available");
        }
    }

    @Override
    public void onStart() {
        publishBinderService(WaydroidContextConstants.WAYDROID_PLATFORM_SERVICE, mPlatformService);
        if (mContext != null) {
            mUM = UserMonitor.getInstance(mContext);
            mWaydroidHardware = Hardware.getInstance(mContext);
            if (SystemProperties.getBoolean("persist.waydroid.forward_notifications", false)) {
                try {
                    mWaydroidNotifications = Notifications.getInstance(mContext);
                } catch (Exception e) {
                    Log.w(TAG, e.getMessage());
                }
            }
        } else {
            Log.w(TAG, "No context available");
        }
        if (mUM != null) {
            registerPackageMonitor();
        }
        if (mWaydroidNotifications != null) {
            registerNotificationListener();
        }
        if (mWaydroidHardware != null) {
            registerShutdownHandler();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            clearTaskSnapshots(); // task IDs restart per boot
            registerTaskStackMonitor();
            registerWindowHalNotification();
        }
        if (phase == PHASE_BOOT_COMPLETED) {
            mNotificationManager = mContext.getSystemService(NotificationManager.class);

            if (mUM == null && !SystemProperties.get("waydroid.tools_version").isEmpty()) {
                Log.w(TAG, "Waydroid integration is not functional");
                showNotification(
                    INTEGRATION_WARN_ID,
                    mContext.getString(R.string.broken_waydroid_integration_title),
                    mContext.getString(R.string.broken_waydroid_integration_msg),
                    mContext.getString(R.string.broken_waydroid_integration_url)
                );
            }

            if (!new File("/dev/dma_heap/system").exists()) {
                Log.w(TAG, "DMA-BUF system heap is missing");
                showNotification(
                    DMABUF_WARN_ID,
                    mContext.getString(R.string.dmabuf_missing_title),
                    mContext.getString(R.string.dmabuf_missing_msg),
                    mContext.getString(R.string.dmabuf_missing_url)
                );
            }

            if (SystemProperties.get("ro.hardware.egl").equals("angle") && SystemProperties.get("ro.hardware.vulkan").equals("pastel")) {
                if (SystemProperties.getBoolean("ro.waydroid.unsupported_nvidia_kmd", false)) {
                    Log.w(TAG, "Unsupported NVIDIA kernel driver detected");
                    showNotification(
                        SW_RENDERING_WARN_ID,
                        mContext.getString(R.string.unsupported_nvidia_kmd_title),
                        mContext.getString(R.string.unsupported_nvidia_kmd_msg),
                        mContext.getString(R.string.unsupported_nvidia_kmd_url)
                    );
                } else {
                    Log.w(TAG, "Waydroid is running without GPU acceleration");
                    showNotification(
                        SW_RENDERING_WARN_ID,
                        mContext.getString(R.string.sw_rendering_title),
                        mContext.getString(R.string.sw_rendering_msg),
                        mContext.getString(R.string.sw_rendering_url)
                    );
                }
            }
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        List<ApplicationInfo> apps = mPm.getInstalledApplications(0);
        for (int n = 0; n < apps.size(); n++) {
            ApplicationInfo appInfo = apps.get(n);

            Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
            if (launchIntent == null) {
                continue;
            }
            saveApplicationIcon(appInfo.packageName);
        }
        if (mUM != null) {
            mUM.userUnlocked(user.getUserIdentifier());
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = mPm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (defaultLauncher.activityInfo != null) {
            String nameOfLauncherPkg = defaultLauncher.activityInfo.packageName;
            SystemProperties.set("waydroid.blacklist_apps", nameOfLauncherPkg);
        }
    }

    private boolean isTv() {
        return mPm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void createNotificationChannelIfNeeded() {
        String id = !isTv() ? WAYDROID_CHANNEL_ID : WAYDROID_CHANNEL_ID_TV;

        if (mNotificationManager.getNotificationChannel(id) != null) {
            return;
        }

        String name = mContext.getString(R.string.waydroid_notification_channel);
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setBlockable(true);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showNotification(int notificationId, String title, String message, String url) {
        createNotificationChannelIfNeeded();

        Notification.Builder notification = new Notification.Builder(mContext, WAYDROID_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setColor(mContext.getColor(R.color.color_error))
                .setSmallIcon(R.drawable.ic_warning)
                .extend(new Notification.TvExtender().setChannelId(WAYDROID_CHANNEL_ID_TV));

        if (url != null) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 0,
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            notification.setContentIntent(pendingIntent);
        }

        mNotificationManager.notify(notificationId, notification.build());
    }

    /* While asleep SurfaceFlinger stops compositing, so an activity started
     * from the host never produces a window on the Wayland side. Wake up
     * first so host-initiated launches always surface. */
    private void wakeUpDevice(String details) {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            pm.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION, details);
        }
    }

    private Intent getAppLaunchIntent(String packageName) {
        Intent launchIntent = null;

        if (isTv()) {
            launchIntent = mPm.getLeanbackLaunchIntentForPackage(packageName);
        }

        if (launchIntent == null) {
            launchIntent = mPm.getLaunchIntentForPackage(packageName);
        }

        return launchIntent;
    }

    /* Most recent task of the package, or -1. getTasks is ordered
     * most-recent-first, so the first match is the one to front. */
    private int findTaskForPackage(String packageName) {
        try {
            List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    ActivityTaskManager.getService().getTasks(MAX_SNAPSHOT_TASKS,
                            false /* filterOnlyVisibleRecents */,
                            false /* keepIntentExtra */,
                            android.view.Display.INVALID_DISPLAY);
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                if (packageMatches(t.topActivity, packageName)
                        || packageMatches(t.realActivity, packageName)
                        || packageMatches(t.baseActivity, packageName))
                    return t.taskId;
            }
        } catch (Exception e) {
            Log.w(TAG, "findTaskForPackage failed: " + e);
        }
        return -1;
    }

    private static boolean packageMatches(ComponentName component, String packageName) {
        return component != null && packageName.equals(component.getPackageName());
    }

    /* Front the app's existing task, or start its launcher activity if it has
     * none. Fronting rather than restarting keeps the task's back stack, which
     * is what tapping a host icon for a running app should do. */
    private boolean frontOrLaunch(String packageName, String reason) {
        if (mPm == null || mContext == null)
            return false;

        Intent launchIntent = getAppLaunchIntent(packageName);
        if (launchIntent == null) {
            Log.w(TAG, "no launchable activity for " + packageName);
            return false;
        }

        wakeUpDevice(reason);

        int taskId = findTaskForPackage(packageName);
        if (taskId > 0) {
            try {
                ActivityTaskManager.getService().moveTaskToFront(null /* appThread */,
                        "android", taskId, 0 /* flags */, null /* options */);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "moveTaskToFront(" + taskId + ") failed: " + e);
            }
        }

        mContext.startActivity(launchIntent);
        return true;
    }

    private void saveApplicationIcon(String packageName) {
        Drawable icon = null;
        try {
            icon = mPm.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException ex) {
            return;
        }
        if (icon == null)
            return;

        Bitmap iconBitmap = drawableToBitmap(icon, false);
        File imageFile = new File(ICONS_DIR, packageName + ".png");
        FileOutputStream fileOutStream = null;
        try {
            fileOutStream = new FileOutputStream(imageFile);
            iconBitmap.compress(Bitmap.CompressFormat.PNG, 90, fileOutStream);
            fileOutStream.close();
        } catch (IOException e) {
            Log.e("app", e.getMessage());
            if (fileOutStream != null) {
                try {
                    fileOutStream.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
        imageFile.setReadable(true, false);
        imageFile.setWritable(true, false);
    }

    private Bitmap drawableToBitmap(Drawable drawable, boolean force) {
        if (!force && drawable instanceof BitmapDrawable)
            return ((BitmapDrawable)drawable).getBitmap();

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    private void registerPackageMonitor() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                if (mUM != null) {
                    mUM.packageStateChanged(UserMonitor.WAYDROID_PACKAGE_ADDED, packageName, uid);
                }
                saveApplicationIcon(packageName);
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                if (mUM != null) {
                    mUM.packageStateChanged(UserMonitor.WAYDROID_PACKAGE_REMOVED, packageName, uid);
                }
                File appIcon = new File(ICONS_DIR + "/" + packageName + ".png");
                if (appIcon.exists())
                    appIcon.delete();
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                if (mUM != null) {
                    mUM.packageStateChanged(UserMonitor.WAYDROID_PACKAGE_UPDATED, packageName, uid);
                }
                saveApplicationIcon(packageName);
            }
        };

        monitor.register(mContext, BackgroundThread.getHandler().getLooper(), UserHandle.ALL, true);
    }

    private static long idCounter = 0;
    public static String nextId() {
        return String.valueOf(idCounter++);
    }

    private void registerNotificationListener() {
        mNotificationIdMap.clear();
        mNotificationActionsMap.clear();
        mSystemNotificationListener = new NotificationListenerService() {
            private INotifications.ImageData getImageData(Notification notification) {
                Icon icon = notification.getLargeIcon();
                if (icon == null) {
                    return null;
                }

                Drawable drawable = icon.loadDrawable(mContext);
                Bitmap bitmap = drawableToBitmap(drawable, true);
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int channels = 4;
                int rowstride = width * channels;
                int pixels[] = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                byte bytes[] = new byte[rowstride * height];
                for (int i = 0; i < pixels.length; i++) {
                    int color = pixels[i];
                    bytes[0 + i * channels] = (byte)((color >> 16) & 0xff); // B
                    bytes[1 + i * channels] = (byte)((color >> 8) & 0xff);  // G
                    bytes[2 + i * channels] = (byte)((color >> 0) & 0xff);  // R
                    bytes[3 + i * channels] = (byte)((color >> 24) & 0xff); // A
                }

                INotifications.ImageData imageData = new INotifications.ImageData();
                imageData.width = width;
                imageData.height = height;
                imageData.rowstride = rowstride;
                imageData.has_alpha = true;
                imageData.data = bytes;
                return imageData;
            }

            @Override
            public void onListenerConnected() {
                Log.i(TAG, "Connected to system notification manager");
            }

            @Override
            public void onNotificationPosted(StatusBarNotification sbn) {
                String key = sbn.getKey();
                String packageName = sbn.getPackageName();
                Notification notification = sbn.getNotification();

                if (notification.isForegroundService()) {
                    return;
                }

                String appName = _getAppName(packageName);
                String summary = notification.extras.getString(Notification.EXTRA_TITLE, "");
                String body = notification.extras.getString(Notification.EXTRA_TEXT, "");

                List<INotifications.Action> hostActions = new LinkedList<INotifications.Action>();
                Map<String, PendingIntent> actionMap = new HashMap<String, PendingIntent>();

                if (notification.contentIntent != null) {
                    INotifications.Action defaultAction = new INotifications.Action();
                    defaultAction.id = "default";
                    defaultAction.label = "";
                    hostActions.add(defaultAction);
                    actionMap.put(defaultAction.id, notification.contentIntent);
                }

                if (notification.actions != null) {
                    for (Notification.Action androidAction : notification.actions) {
                        if (androidAction.actionIntent != null) {
                            String label = androidAction.title.toString();
                            String hostActionId = nextId();

                            INotifications.Action hostAction = new INotifications.Action();
                            hostAction.id = hostActionId;
                            hostAction.label = label;

                            hostActions.add(hostAction);
                            actionMap.put(hostActionId, androidAction.actionIntent);
                        }
                    }
                }

                INotifications.ImageData image = getImageData(notification);

                String category = ""; // TODO: try to map Android category to FDO category
                boolean suppressSound = true; // TODO: consider playing the sound on the desktop and silencing android instead
                int expireTimeout = INotifications.TIMEOUT_DEFAULT;
                boolean isResident = true;
                boolean isTransient = false;
                byte urgency = INotifications.Urgency.NORMAL; // TODO: try to map Android importance to FDO urgency

                int existingId = mNotificationIdMap.getOrDefault(key, INotifications.ID_NONE);

                int newId = mWaydroidNotifications.notify(existingId, appName, packageName, summary, body,
                                              hostActions, image, category, suppressSound, expireTimeout,
                                              isResident, isTransient, urgency);
                if (newId == INotifications.ID_NONE) {
                    return;
                }

                mNotificationIdMap.put(key, newId);
                mNotificationActionsMap.put(newId, actionMap);
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn) {
                String key = sbn.getKey();
                Integer id = mNotificationIdMap.get(key);
                mNotificationIdMap.remove(key);
                if (id != null) {
                    mNotificationActionsMap.remove(id);
                    mWaydroidNotifications.closeNotification(id);
                }
            }
        };

        try {
            mSystemNotificationListener.registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register system notification listener", e);
        }

        mWaydroidNotifications.registerListener(new INotifications.INotificationCallback.Stub() {
            @Override
            public void onActionInvoked(int notificationId, String actionId, String xdgActivationToken) {
                Map<String, PendingIntent> innerMap = mNotificationActionsMap.get(notificationId);
                if (innerMap == null)
                    return;
                PendingIntent intent = innerMap.get(actionId);
                if (intent == null)
                    return;

                try {
                    intent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.i(TAG, "Canceled notification action: " + e.getMessage());
                }

                // TODO: Activate window through hwcomposer
            }
        });
    }

    /* Per-app window mode keys off waydroid.active_apps, but only host-side
     * launches write it. Android-side navigation (in-app intents, notifications,
     * task switches) leaves it stale, and cards/input then target the wrong app.
     * Publish the real foreground task whenever the task stack changes. */
    private void registerTaskStackMonitor() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskStackChanged() {
                    BackgroundThread.getHandler().post(WayDroidService.this::syncActiveApps);
                }

                @Override
                public void onTaskMovedToFront(int taskId) {
                    BackgroundThread.getHandler().post(WayDroidService.this::syncActiveApps);
                }

                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    BackgroundThread.getHandler().post(
                            () -> pushTaskCreated(taskId, componentName));
                }

                @Override
                public void onTaskRemovalStarted(
                        android.app.ActivityManager.RunningTaskInfo taskInfo) {
                    BackgroundThread.getHandler().post(
                            () -> pushTaskRemoved(taskInfo.taskId));
                }

                @Override
                public void onTaskRemoved(int taskId) {
                    BackgroundThread.getHandler().post(() -> {
                        pushTaskRemoved(taskId);
                        publishTaskList();
                    });
                }

                @Override
                public void onTaskFocusChanged(int taskId, boolean focused) {
                    BackgroundThread.getHandler().post(
                            () -> pushTaskFocusChanged(taskId, focused));
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register task stack listener", e);
        }
    }

    /* Task control plane: push task lifecycle into the hwcomposer so it stops
     * inferring lifecycle from per-frame layer names. The HAL restarts with
     * the framework (and on its own crashes), so resync the full task table
     * every time its service (re)registers. */
    private IWaydroidWindow mWindowHal;

    private synchronized IWaydroidWindow getWindowHal() {
        if (mWindowHal == null) {
            try {
                mWindowHal = IWaydroidWindow.getService(false /* retry */);
                if (mWindowHal != null) {
                    mWindowHal.linkToDeath(cookie -> {
                        synchronized (WayDroidService.this) {
                            mWindowHal = null;
                        }
                    }, 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "Waydroid window HAL unavailable: " + e);
            }
        }
        return mWindowHal;
    }

    private synchronized void dropWindowHal() {
        mWindowHal = null;
    }

    private void registerWindowHalNotification() {
        try {
            IServiceManager.getService().registerForNotifications(
                    "vendor.waydroid.window@1.3::IWaydroidWindow", "default",
                    new IServiceNotification.Stub() {
                        @Override
                        public void onRegistration(String fqName, String name,
                                boolean preexisting) {
                            Log.i(TAG, "Waydroid window HAL up, resyncing task table");
                            dropWindowHal();
                            BackgroundThread.getHandler().post(
                                    WayDroidService.this::resyncTasks);
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "Unable to watch for the window HAL: " + e);
        }
    }

    private static final int MAX_SNAPSHOT_TASKS = 200;

    private int mTaskGeneration;

    /* Send the whole table in one call: replaying taskCreated per task could
     * not tell the HAL which of its entries WMS had since dropped, so a lost
     * taskRemoved leaked an entry (and its close-pending mark) forever. */
    private void resyncTasks() {
        IWaydroidWindow hal = getWindowHal();
        if (hal == null)
            return;
        try {
            List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    ActivityTaskManager.getService().getTasks(MAX_SNAPSHOT_TASKS,
                            false /* filterOnlyVisibleRecents */,
                            true /* keepIntentExtra */,
                            android.view.Display.INVALID_DISPLAY);
            if (tasks.size() >= MAX_SNAPSHOT_TASKS) {
                /* A truncated list would read as "these tasks are gone" and
                 * close live cards. */
                Log.w(TAG, "Task list hit the " + MAX_SNAPSHOT_TASKS
                        + " cap; skipping the snapshot");
                return;
            }
            ActivityTaskManager.RootTaskInfo focusedRoot =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            int focusedId = focusedRoot != null ? focusedRoot.taskId : -1;

            ArrayList<IWaydroidWindow.TaskInfo> snapshot = new ArrayList<>(tasks.size());
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                ComponentName c = t.realActivity != null ? t.realActivity : t.baseActivity;
                IWaydroidWindow.TaskInfo info = new IWaydroidWindow.TaskInfo();
                info.taskID = t.taskId;
                info.packageName = c != null ? c.getPackageName() : "";
                info.componentName = c != null ? c.flattenToShortString() : "";
                info.focused = t.isFocused || t.taskId == focusedId;
                snapshot.add(info);
            }
            hal.taskListSnapshot(++mTaskGeneration, snapshot);
            Log.i(TAG, "Snapshot #" + mTaskGeneration + ": resynced "
                    + snapshot.size() + " tasks to the window HAL");
        } catch (Exception e) {
            Log.w(TAG, "Task table resync failed: " + e);
            dropWindowHal();
        }
    }

    private void pushTaskCreated(int taskId, ComponentName component) {
        IWaydroidWindow hal = getWindowHal();
        if (hal == null)
            return;
        String pkg = component != null ? component.getPackageName() : "";
        String comp = component != null ? component.flattenToShortString() : "";
        Log.i(TAG, "taskCreated " + taskId + " " + comp);
        try {
            hal.taskCreated(taskId, pkg, comp);
        } catch (Exception e) {
            Log.w(TAG, "taskCreated push failed: " + e);
            dropWindowHal();
        }
    }

    private void pushTaskRemoved(int taskId) {
        IWaydroidWindow hal = getWindowHal();
        if (hal == null)
            return;
        Log.i(TAG, "taskRemoved " + taskId);
        try {
            hal.taskRemoved(taskId);
        } catch (Exception e) {
            Log.w(TAG, "taskRemoved push failed: " + e);
            dropWindowHal();
        }
    }

    private void pushTaskFocusChanged(int taskId, boolean focused) {
        IWaydroidWindow hal = getWindowHal();
        if (hal == null)
            return;
        Log.i(TAG, "taskFocusChanged " + taskId + " focused=" + focused);
        try {
            hal.taskFocusChanged(taskId, focused);
        } catch (Exception e) {
            Log.w(TAG, "taskFocusChanged push failed: " + e);
            dropWindowHal();
        }
    }

    /* Live task IDs, so the hwcomposer can close cards whose task is gone
     * (a dead task's card otherwise lingers forever, and its open.<pkg> prop
     * makes the host launcher think the app is still running — icon clicks
     * then focus the zombie card instead of launching). */
    private void publishTaskList() {
        try {
            List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    ActivityTaskManager.getService().getTasks(200,
                            false /* filterOnlyVisibleRecents */,
                            true /* keepIntentExtra */,
                            android.view.Display.INVALID_DISPLAY);
            StringBuilder sb = new StringBuilder();
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                if (sb.length() > 0)
                    sb.append(',');
                sb.append(t.taskId);
            }
            SystemProperties.set("waydroid.task_list", sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "publishTaskList failed: " + e);
        }
    }

    private int mLastForegroundTaskId = -1;

    private void syncActiveApps() {
        publishTaskList();

        String current = SystemProperties.get("waydroid.active_apps", "none");
        // Only per-app mode follows the foreground task; closed ("none") and
        // full-ui ("Waydroid") are host requests, not app switches
        if (current.equals("none") || current.equals("Waydroid"))
            return;

        String pkg;
        int taskId;
        try {
            ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null)
                return;
            pkg = info.topActivity.getPackageName();
            taskId = info.taskId;
        } catch (RemoteException e) {
            return;
        }

        /* Any focus change means the previous foreground task just went to the
         * background: save its WMS snapshot (taken by SnapshotController at
         * transition start, i.e. with correct pre-switch content) for the
         * hwcomposer to freeze the card with. */
        if (taskId != mLastForegroundTaskId) {
            final int outgoing = mLastForegroundTaskId;
            mLastForegroundTaskId = taskId;
            if (outgoing > 0)
                saveTaskSnapshot(outgoing);
        }

        if (pkg.equals(current) || pkg.equals("android"))
            return;
        // The hidden launcher in front means "everything backgrounded", not an app switch
        for (String hidden : SystemProperties.get("waydroid.blacklist_apps", "").split(":")) {
            if (pkg.equals(hidden))
                return;
        }
        Log.i(TAG, "Foreground task changed, active_apps " + current + " -> " + pkg);
        SystemProperties.set("waydroid.active_apps", pkg);
    }

    private static final String SNAPSHOTS_DIR = "/data/waydroid_snapshots";
    private static final int SNAPSHOT_MAGIC = 0x57444150; // "WDAP"

    /* Dump a task's snapshot as raw RGBA (16-byte header: magic, width,
     * height, rowBytes) where the hwcomposer can pick it up. Task IDs restart
     * per boot, so the directory is wiped in onStart. */
    private void saveTaskSnapshot(int taskId) {
        try {
            TaskSnapshot snap = ActivityTaskManager.getService()
                    .getTaskSnapshot(taskId, false /* isLowResolution */);
            if (snap == null) {
                /* Nothing recorded (our launcher-less setup rarely triggers
                 * SnapshotController); render the task's layers now instead.
                 * Forced capture during a display-off transition deadlocked
                 * system_server into a watchdog kill (blocked android.display
                 * + AMS for 71s), and a task mid-teardown is equally unsafe —
                 * only capture live tasks on an interactive display. */
                PowerManager pm = mContext.getSystemService(PowerManager.class);
                if (pm == null || !pm.isInteractive())
                    return;
                boolean alive = false;
                for (android.app.ActivityManager.RunningTaskInfo t :
                        ActivityTaskManager.getService().getTasks(200, false, true,
                                android.view.Display.INVALID_DISPLAY)) {
                    if (t.taskId == taskId && t.isRunning) {
                        alive = true;
                        break;
                    }
                }
                if (!alive)
                    return;
                snap = ActivityTaskManager.getService()
                        .takeTaskSnapshot(taskId, false /* updateCache */);
            }
            if (snap == null) {
                Log.w(TAG, "No snapshot obtainable for task " + taskId);
                return;
            }
            HardwareBuffer hwBuffer = snap.getHardwareBuffer();
            if (hwBuffer == null)
                return;
            Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, snap.getColorSpace());
            if (hwBitmap == null)
                return;
            Bitmap bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
            hwBitmap.recycle();
            if (bitmap == null)
                return;

            File dir = new File(SNAPSHOTS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
                dir.setReadable(true, false);
                dir.setExecutable(true, false);
            }
            ByteBuffer pixels = ByteBuffer.allocate(bitmap.getRowBytes() * bitmap.getHeight());
            bitmap.copyPixelsToBuffer(pixels);
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(SNAPSHOT_MAGIC);
            header.putInt(bitmap.getWidth());
            header.putInt(bitmap.getHeight());
            header.putInt(bitmap.getRowBytes());

            File tmp = new File(dir, "." + taskId + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(header.array());
                out.write(pixels.array());
            }
            File dst = new File(dir, taskId + ".raw");
            tmp.renameTo(dst);
            dst.setReadable(true, false);
            bitmap.recycle();
            Log.i(TAG, "Saved snapshot of task " + taskId);
        } catch (Exception e) {
            Log.w(TAG, "Snapshot of task " + taskId + " failed: " + e);
        }
    }

    private void clearTaskSnapshots() {
        File[] files = new File(SNAPSHOTS_DIR).listFiles();
        if (files == null)
            return;
        for (File f : files)
            f.delete();
    }

    private void registerShutdownHandler() {
        IntentFilter filter = new IntentFilter();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Android is shutting down");
                mWaydroidHardware.shutdownRequest(SystemProperties.get("sys.shutdown.requested"));
            }
        };

        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private String _getAppName(String packageName) {
        if (mPm == null || mContext == null)
            return "";

        ApplicationInfo appInfo;
        try {
            appInfo = mPm.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            Log.e(TAG, e.getMessage());
            return "";
        }
        String name = appInfo.name;
        CharSequence label = appInfo.loadLabel(mPm);
        if (label != null)
            name = label.toString();

        return name;
    }

    /* Service */
    private final IBinder mPlatformService = new IPlatform.Stub() {
        /* Calls arrive from the Waydroid host, whose uid means nothing to
         * Android, so anything that reaches a framework service checking the
         * caller (startActivity, Settings writes, PowerManager) is refused with
         * a SecurityException. Run every method with the identity of the system
         * process we already live in. */
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            final long ident = Binder.clearCallingIdentity();
            try {
                return super.onTransact(code, data, reply, flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public String getprop(String prop, String default_value) {
            return SystemProperties.get(prop, default_value);
        }

        @Override
        public void setprop(String prop, String value) {
            SystemProperties.set(prop, value);
        }

        @Override
        public List<AppInfo> getAppsInfo() {
            List<AppInfo> result = new ArrayList<>();

            if (mPm == null)
                return result;

            List<ApplicationInfo> apps = mPm.getInstalledApplications(0);
            for (int n = 0; n < apps.size(); n++) {
                ApplicationInfo appInfo = apps.get(n);

                Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
                if (launchIntent == null) {
                    continue;
                }

                String name = appInfo.name;
                CharSequence label = appInfo.loadLabel(mPm);
                if (label != null)
                    name = label.toString();

                AppInfo info = new AppInfo();
                info.name = name;
                info.packageName = appInfo.packageName;
                info.action = launchIntent.getAction();
                if (launchIntent.getData() != null)
                    info.launchIntent = launchIntent.getData().toString();
                else
                    info.launchIntent = "";

                info.componentClassName = launchIntent.getComponent().getClassName();
                info.componentPackageName = launchIntent.getComponent().getPackageName();
                info.categories = new ArrayList<String>(launchIntent.getCategories());
                result.add(info);
            }
            return result;
        }

        @Override
        public AppInfo getAppInfo(String packageName) {
            if (mPm == null)
                return null;

            ApplicationInfo appInfo;
            try {
                appInfo = mPm.getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                return null;
            }
            Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
            if (launchIntent == null) {
                return null;
            }

            String name = appInfo.name;
            CharSequence label = appInfo.loadLabel(mPm);
            if (label != null)
                name = label.toString();

            AppInfo info = new AppInfo();
            info.name = name;
            info.packageName = appInfo.packageName;
            info.action = launchIntent.getAction();
            if (launchIntent.getData() != null)
                info.launchIntent = launchIntent.getData().toString();
            else
                info.launchIntent = "";

            info.componentClassName = launchIntent.getComponent().getClassName();
            info.componentPackageName = launchIntent.getComponent().getPackageName();
            info.categories = new ArrayList<String>(launchIntent.getCategories());
            return info;
        }

        @Override
        public int installApp(String path) {
            int ret = 0;
            final Uri packageURI;

            // Populate apkURI, must be present
            if (path != null) {
                packageURI = Uri.fromFile(new File(path));
            } else {
                return -1;
            }

            final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            final PackageInstaller packageInstaller = mPm.getPackageInstaller();
            PackageInstaller.Session session = null;
            try {
                final int sessionId = packageInstaller.createSession(params);
                final byte[] buffer = new byte[65536];
                session = packageInstaller.openSession(sessionId);
                final InputStream in = mContext.getContentResolver().openInputStream(packageURI);
                final OutputStream out = session.openWrite("PackageInstaller", 0, -1 /* sizeBytes, unknown */);
                try {
                    int c;
                    while ((c = in.read(buffer)) != -1) {
                        out.write(buffer, 0, c);
                    }
                    session.fsync(out);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                }
                // Create a PendingIntent and use it to generate the IntentSender
                Intent broadcastIntent = new Intent(BROADCAST_ACTION_INSTALL);
                broadcastIntent.setPackage(mContext.getPackageName());
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        mContext,
                        sessionId,
                        broadcastIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pendingIntent.getIntentSender());
            } catch (Exception e) {
                Log.e(TAG, "Failure", e);
                ret = -1;
            } finally {
                IoUtils.closeQuietly(session);
            }

            return ret;
        }

        @Override
        public int removeApp(String packageName) {
          final PackageInstaller packageInstaller = mPm.getPackageInstaller();

          mPm.setInstallerPackageName(packageName, mContext.getPackageName());
          // Create a PendingIntent and use it to generate the IntentSender
          Intent broadcastIntent = new Intent(BROADCAST_ACTION_UNINSTALL);
          PendingIntent pendingIntent = PendingIntent.getBroadcast(
                  mContext, // context
                  0, // arbitary
                  broadcastIntent,
                  PendingIntent.FLAG_UPDATE_CURRENT);
          packageInstaller.uninstall(packageName, pendingIntent.getIntentSender());

          return 0;
        }

        @Override
        public void launchApp(String packageName) {
            frontOrLaunch(packageName, "waydroid:launchApp");
        }

        /* The host used to set waydroid.active_apps itself and then call
         * launchApp. Two calls means two truths: a launch that failed left the
         * mode pointing at an app with no window, which fail-closes the
         * hwcomposer's task gate and stops anything rendering. */
        @Override
        public boolean showApp(String packageName) {
            if (mPm == null || mContext == null
                    || getAppLaunchIntent(packageName) == null) {
                Log.w(TAG, "showApp: nothing launchable for " + packageName);
                return false;
            }
            /* Mode first: the hwcomposer only creates a window for the app it
             * is already in per-app mode for. */
            SystemProperties.set("waydroid.active_apps", packageName);
            return frontOrLaunch(packageName, "waydroid:showApp");
        }

        @Override
        public void showFullUI() {
            wakeUpDevice("waydroid:showFullUI");
            SystemProperties.set("waydroid.active_apps", "Waydroid");
        }

        @Override
        public String launchIntent(String action, String uri) {
            if (mPm == null || mContext == null)
                return "";

            Intent i;
            if (uri == null || uri.isEmpty())
                i = new Intent(action);
            else
                i = new Intent(action, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ResolveInfo ri = mPm.resolveActivity(i, 0);
            try {
                wakeUpDevice("waydroid:launchIntent");
                mContext.startActivity(i);
            } catch (ActivityNotFoundException ignored) {}

            if (ri != null) {
                return ri.activityInfo.packageName;
            }
            return "";
        }

        @Override
        public String getAppName(String packageName) {
            return _getAppName(packageName);
        }

        @Override
        public void settingsPutString(int mode, String key, String value) {
            if (mode == Platform.SETTINGS_SECURE) {
                Settings.Secure.putString(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_SYSTEM) {
                Settings.System.putString(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_GLOBAL) {
                Settings.Global.putString(mContext.getContentResolver(), key, value);
            }
        }

        @Override
        public String settingsGetString(int mode, String key) {
            if (mode == Platform.SETTINGS_SECURE) {
                return Settings.Secure.getString(mContext.getContentResolver(), key);
            } else if (mode == Platform.SETTINGS_SYSTEM) {
                return Settings.System.getString(mContext.getContentResolver(), key);
            } else if (mode == Platform.SETTINGS_GLOBAL) {
                return Settings.Global.getString(mContext.getContentResolver(), key);
            }
            return "";
        }

        @Override
        public void settingsPutInt(int mode, String key, int value) {
            if (mode == Platform.SETTINGS_SECURE) {
                Settings.Secure.putInt(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_SYSTEM) {
                Settings.System.putInt(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_GLOBAL) {
                Settings.Global.putInt(mContext.getContentResolver(), key, value);
            }
        }

        @Override
        public int settingsGetInt(int mode, String key) {
            try {
                if (mode == Platform.SETTINGS_SECURE) {
                    return Settings.Secure.getInt(mContext.getContentResolver(), key);
                } else if (mode == Platform.SETTINGS_SYSTEM) {
                    return Settings.System.getInt(mContext.getContentResolver(), key);
                } else if (mode == Platform.SETTINGS_GLOBAL) {
                    return Settings.Global.getInt(mContext.getContentResolver(), key);
                }
            } catch (Settings.SettingNotFoundException e) {
                Log.e(TAG, e.getMessage());
            }
            return Platform.ERROR_UNDEFINED;
        }
    };
}
