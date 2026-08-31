# Components declared in AndroidManifest.xml (the ContentProvider, the Service,
# the Activity, the Application) are kept automatically by AGP, so they need no
# rules here.

# The provider hands WhatsApp a cursor whose column names are string literals
# rather than symbols, so nothing about it is reflective and it shrinks safely.

# Coil and kotlinx-coroutines ship their own consumer rules.

# Strip logging from release builds. Nothing in this app logs user content, but
# removing the calls entirely means a future careless Log.d cannot leak one.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# org.json is part of the platform.
-dontwarn org.json.**
