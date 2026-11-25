package com.trackershield.demo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.net.InetAddress;

/**
 * Pretends to be an app with trackers: it looks up tracker servers every few seconds,
 * including after you leave it, so the TrackerShield test can check that it names this app
 * and notices the background tracking.
 */
public class DemoActivity extends Activity {
    private static final String[] TRACKERS = {
            "app-measurement.com", "t.appsflyer.com", "graph.facebook.com", "api.mixpanel.com",
            "o4504.ingest.us.sentry.io"};
    private static Thread worker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("Tracker Demo (test app): contacting tracker servers every 3 seconds.");
        t.setPadding(40, 40, 40, 40);
        setContentView(t);

        // Built-in "tracking libraries" (empty stand-ins with the real class names).
        new com.appsflyer.AppsFlyerLib();
        new com.google.firebase.analytics.FirebaseAnalytics();
        new com.facebook.appevents.AppEventsLogger();
        new io.sentry.Sentry();

        synchronized (DemoActivity.class) {
            if (worker != null) return;
            worker = new Thread(() -> {
                for (int round = 0; round < 40; round++) {
                    for (String host : TRACKERS) {
                        try {
                            InetAddress.getByName(host);
                            Log.i("TrackerDemo", host + " reachable");
                        } catch (Exception e) {
                            Log.i("TrackerDemo", host + " blocked");
                        }
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "tracker-demo");
            worker.start();
        }
    }
}
