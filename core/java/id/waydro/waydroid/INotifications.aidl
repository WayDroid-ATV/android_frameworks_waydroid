package id.waydro.waydroid;

interface INotifications {
    void registerListener(in INotificationCallback listener);

    int notify(in int replaces_id,
               in String app_name,
               in String package_name,
               in String summary,
               in String body,
               in List<Action> actions,
               in @nullable ImageData image,
               in String category,
               in boolean suppress_sound,
               in int expire_timeout,
               in boolean is_resident,
               in boolean is_transient,
               in Urgency urgency);

    void closeNotification(in int notification_id);

    interface INotificationCallback {
        void onActionInvoked(int notification_id, String action, String xdg_activation_token);
    }

    parcelable Action {
        String id;
        String label;
    }

    @Backing(type="byte")
    enum Urgency {
        LOW = 0,
        NORMAL = 1,
        CRITICAL = 2,
    }

    parcelable ImageData {
        int width;
        int height;
        int rowstride;
        boolean has_alpha;
        byte[] data;
    }

    const int ID_NONE = 0;
    const int TIMEOUT_DEFAULT = -1;
}
