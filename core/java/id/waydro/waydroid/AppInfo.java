package id.waydro.waydroid;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

public final class AppInfo implements Parcelable {
    public String name;
    public String packageName;
    public String action;
    public String launchIntent;
    public String componentPackageName;
    public String componentClassName;
    public List<String> categories;

    public static final Parcelable.Creator<AppInfo> CREATOR = new Parcelable.Creator<AppInfo>() {
        public AppInfo createFromParcel(Parcel in) {
            return new AppInfo(in);
        }

        public AppInfo[] newArray(int size) {
            return new AppInfo[size];
        }
    };

    public AppInfo() {
    }

    private AppInfo(Parcel in) {
        readFromParcel(in);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(name);
        out.writeString(packageName);
        out.writeString(action);
        out.writeString(launchIntent);
        out.writeString(componentPackageName);
        out.writeString(componentClassName);
        out.writeStringList(categories);
    }

    public void readFromParcel(Parcel in) {
        name = in.readString();
        packageName = in.readString();
        action = in.readString();
        launchIntent = in.readString();
        componentPackageName = in.readString();
        componentClassName = in.readString();
        in.readStringList(categories);
    }

    public int describeContents() {
        return 0;
    }
}
