package android.net;

/**
 * Concrete non-abstract Uri subclass for JVM unit tests.
 */
public class TestUri extends Uri {
    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String toString() { return "content://media/external/audio/albumart/1"; }
    @Override public Builder buildUpon() { return null; }
    @Override public String getAuthority() { return "media"; }
    @Override public String getEncodedAuthority() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public String getEncodedPath() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getEncodedSchemeSpecificPart() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getHost() { return null; }
    @Override public String getLastPathSegment() { return null; }
    @Override public String getPath() { return null; }
    @Override public java.util.List<String> getPathSegments() { return null; }
    @Override public int getPort() { return -1; }
    @Override public String getQuery() { return null; }
    @Override public String getQueryParameter(String key) { return null; }
    @Override public java.util.List<String> getQueryParameters(String key) { return null; }
    @Override public String getScheme() { return "content"; }
    @Override public String getSchemeSpecificPart() { return null; }
    @Override public String getUserInfo() { return null; }
    @Override public int compareTo(Uri other) { return 0; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
    @Override public boolean equals(Object o) { return this == o; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
