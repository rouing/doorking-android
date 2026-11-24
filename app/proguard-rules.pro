# Targeted R8 Full Mode (AGP 9) keep rules for reflectively instantiated AndroidX classes.

# Room / WorkManager internal RoomDatabase subclass (instantiated via Class.getDeclaredConstructor())
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}

# WorkManager workers & input mergers instantiated reflectively by WorkerFactory / InputMergerFactory
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# AndroidX App Startup initializers
-keep class * implements androidx.startup.Initializer {
    public <init>();
}

# Glance ActionCallback public no-arg constructors (mandated by glance-appwidget-updates-and-memory)
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
