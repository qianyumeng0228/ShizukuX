package af.shizuku.common.util;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Compatibility class for UserInfo.
 */
public class UserInfoCompat implements Parcelable {

    public final int id;
    public final String name;
    public final int flags;

    public UserInfoCompat(int id, String name, int flags) {
        this.id = id;
        this.name = name;
        this.flags = flags;
    }

    protected UserInfoCompat(Parcel in) {
        id = in.readInt();
        name = in.readString();
        flags = in.readInt();
    }

    public static final Creator<UserInfoCompat> CREATOR = new Creator<UserInfoCompat>() {
        @Override
        public UserInfoCompat createFromParcel(Parcel in) {
            return new UserInfoCompat(in);
        }

        @Override
        public UserInfoCompat[] newArray(int size) {
            return new UserInfoCompat[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(name);
        dest.writeInt(this.flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserInfoCompat that = (UserInfoCompat) o;
        return id == that.id && flags == that.flags && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, flags);
    }
}
