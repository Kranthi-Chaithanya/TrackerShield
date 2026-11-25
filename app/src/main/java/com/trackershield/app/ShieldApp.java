package com.trackershield.app;

import android.app.Application;

/** Loads the saved tracking history once, before any screen or the VPN needs it. */
public class ShieldApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Settings.loadHistory(this);
    }
}
