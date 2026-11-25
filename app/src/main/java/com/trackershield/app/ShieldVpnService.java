package com.trackershield.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A VPN that never leaves the phone and only carries DNS lookups.
 *
 * Before an app can talk to a server such as "app-measurement.com" it must look up that
 * server's address. With this VPN on, every app's lookups come here. Lookups for tracker
 * domains get a "no such domain" answer, so the app can't send its tracking data. All other
 * lookups are passed to the phone's normal DNS server and answered as usual. The rest of the
 * apps' traffic doesn't go through this VPN at all.
 */
public class ShieldVpnService extends VpnService {
    static final String ACTION_START = "com.trackershield.app.START";
    static final String ACTION_STOP = "com.trackershield.app.STOP";
    static final String ACTION_RESTART = "com.trackershield.app.RESTART";

    private static final String TAG = "TrackerShield";
    // Private addresses that exist only inside the phone: the VPN itself and a stand-in DNS server.
    private static final String VPN_ADDRESS = "10.111.222.1";
    private static final String DNS_ADDRESS = "10.111.222.2";
    private static final int MTU = 4096;
    private static final int UPSTREAM_TIMEOUT_MS = 4000;
    private static final int MAX_PARALLEL_LOOKUPS = 16;
    private static final String CHANNEL_ID = "protection";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFICATION_DELAY_MS = 5000;
    private static final long SAVE_INTERVAL_MS = 30_000;

    private static volatile boolean running;
    private static volatile boolean lockdown;

    /** True while lookups are being filtered. */
    static boolean isRunning() {
        return running;
    }

    /**
     * True if Android's "Block connections without VPN" is on for this app. That setting would
     * cut off all traffic except DNS, because this VPN deliberately carries nothing else.
     */
    static boolean isLockdownOn() {
        return running && lockdown;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object tunWriteLock = new Object();
    private final AtomicBoolean notificationQueued = new AtomicBoolean();

    private ParcelFileDescriptor tun;
    private volatile FileOutputStream tunOut;
    private Thread reader;
    private FileDescriptor wakeRead;
    private FileDescriptor wakeWrite;
    private volatile ThreadPoolExecutor upstreamPool;
    private volatile List<InetAddress> upstreamServers = fallbackServers();
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Blocklist blocklist;
    private volatile ConnectivityManager connectivity;
    private volatile ForegroundWatcher foregroundWatcher;
    private final Runnable saveHistory = new Runnable() {
        @Override
        public void run() {
            Settings.saveHistoryIfChanged(ShieldVpnService.this);
            if (running) mainHandler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutDown();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            if (!running) {
                stopSelf();
                return START_NOT_STICKY;
            }
            stopVpn();
        }
        // ACTION_START, ACTION_RESTART, or Android starting us for "Always-on VPN".
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        // The user switched the VPN off in Android settings, or another VPN app took over.
        stopVpn();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private void shutDown() {
        stopVpn();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private synchronized void startVpn() {
        if (tun != null) return;
        goForeground();
        Settings.loadHistory(this);
        blocklist = Settings.blocklist(this);
        connectivity = getSystemService(ConnectivityManager.class);
        foregroundWatcher = new ForegroundWatcher(this);

        Builder builder = new Builder();
        builder.setSession("TrackerShield");
        builder.addAddress(VPN_ADDRESS, 32);
        builder.addDnsServer(DNS_ADDRESS);
        builder.addRoute(DNS_ADDRESS, 32);          // only DNS lookups enter the VPN
        builder.allowFamily(OsConstants.AF_INET6);  // leave IPv6 traffic alone instead of blocking it
        builder.setMtu(MTU);
        builder.setBlocking(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);              // keep Wi-Fi counted as Wi-Fi, not as mobile data
        }
        builder.setConfigureIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        skip(builder, getPackageName());            // our own lookups go straight out
        for (String pkg : Settings.getSet(this, Settings.KEY_SKIPPED_APPS)) skip(builder, pkg);

        ParcelFileDescriptor pfd = null;
        try {
            pfd = builder.establish();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start the VPN", e);
        }
        if (pfd == null) {
            // VPN permission was taken back, or another app's VPN is locked in place.
            Log.w(TAG, "VPN not established");
            shutDown();
            return;
        }

        FileDescriptor[] pipe;
        try {
            pipe = Os.pipe();
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not create wake-up pipe", e);
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            shutDown();
            return;
        }

        tun = pfd;
        tunOut = new FileOutputStream(pfd.getFileDescriptor());
        wakeRead = pipe[0];
        wakeWrite = pipe[1];
        ThreadPoolExecutor pool = new ThreadPoolExecutor(MAX_PARALLEL_LOOKUPS, MAX_PARALLEL_LOOKUPS,
                30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(256),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        pool.allowCoreThreadTimeOut(true);
        upstreamPool = pool;
        watchNetwork();

        final FileDescriptor tunFd = pfd.getFileDescriptor();
        final FileDescriptor wakeFd = wakeRead;
        reader = new Thread(() -> readLoop(tunFd, wakeFd), "TrackerShield-dns");
        reader.start();
        lockdown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled();
        if (lockdown) Log.w(TAG, "\"Block connections without VPN\" is on: apps will lose internet");
        running = true;
        BlockLog.get().changed();
        postNotification();
        mainHandler.removeCallbacks(saveHistory);
        mainHandler.postDelayed(saveHistory, SAVE_INTERVAL_MS);
    }

    private synchronized void stopVpn() {
        boolean wasRunning = running || tun != null;
        running = false;
        if (networkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            try {
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkCallback = null;
        }
        if (wakeWrite != null) {
            closeQuietly(wakeWrite);    // wakes the reader thread so it can finish
            wakeWrite = null;
        }
        Thread r = reader;
        reader = null;
        if (r != null && r != Thread.currentThread()) {
            try {
                r.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (wakeRead != null) {
            closeQuietly(wakeRead);
            wakeRead = null;
        }
        ThreadPoolExecutor pool = upstreamPool;
        upstreamPool = null;
        if (pool != null) pool.shutdownNow();
        tunOut = null;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        mainHandler.removeCallbacks(saveHistory);
        Settings.saveHistoryIfChanged(this);
        if (wasRunning) BlockLog.get().changed();
    }

    // ---- Packet handling (runs on the reader thread) ----

    private void readLoop(FileDescriptor tunFd, FileDescriptor wakeFd) {
        FileInputStream in = new FileInputStream(tunFd);
        byte[] buf = new byte[32767];
        StructPollfd tunPoll = new StructPollfd();
        tunPoll.fd = tunFd;
        tunPoll.events = (short) OsConstants.POLLIN;
        StructPollfd wakePoll = new StructPollfd();
        wakePoll.fd = wakeFd;
        wakePoll.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = {tunPoll, wakePoll};

        while (true) {
            try {
                tunPoll.revents = 0;
                wakePoll.revents = 0;
                Os.poll(fds, -1);
                if (wakePoll.revents != 0) return;          // stopVpn() asked us to finish
                if ((tunPoll.revents & OsConstants.POLLIN) != 0) {
                    int n = in.read(buf);
                    if (n > 0) handlePacket(buf, n);
                } else if (tunPoll.revents != 0) {
                    break;                                   // the VPN interface went away
                }
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) continue;
                Log.e(TAG, "poll failed", e);
                break;
            } catch (IOException e) {
                Log.e(TAG, "read failed", e);
                break;
            } catch (RuntimeException e) {
                Log.w(TAG, "Skipping a packet that could not be handled", e); // keep protecting
            }
        }
        // Stopped by an error rather than by the user. Switch the VPN off so that lookups go back
        // to normal, instead of leaving the phone with a VPN that no longer answers.
        mainHandler.post(this::shutDown);
    }

    private void handlePacket(byte[] buf, int len) {
        DnsPacket.Query q = DnsPacket.parse(buf, len);
        if (q == null || q.dstPort != 53) return;
        if (q.name != null) {
            Blocklist list = blocklist;
            String owner = list.trackerOwner(q.name);
            if (owner != null) {
                boolean allowed = list.isAllowed(q.name);
                // Ask which app made this lookup while its socket still exists (before we answer).
                int uid = lookupOwnerUid(q);
                BlockLog.get().recordTracker(uid, q.name, owner, !allowed, backgroundState(uid),
                        System.currentTimeMillis());
                if (!allowed) {
                    byte[] answer = DnsPacket.blockedResponse(q);
                    writeToTun(DnsPacket.buildResponsePacket(q, answer, answer.length));
                    scheduleNotificationUpdate();
                    return;
                }
            } else {
                BlockLog.get().recordChecked();
            }
        }
        forward(q);
    }

    /**
     * Which app made this lookup. Android's DNS resolver sends each lookup from a socket owned
     * by the app that asked, and a VPN app may ask Android who owns a connection (Android 10+).
     */
    private int lookupOwnerUid(DnsPacket.Query q) {
        ConnectivityManager cm = connectivity;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || cm == null) return BlockLog.UNKNOWN_UID;
        try {
            return cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                    new InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort),
                    new InetSocketAddress(InetAddress.getByAddress(q.dstIp), q.dstPort));
        } catch (IOException | RuntimeException e) {
            return BlockLog.UNKNOWN_UID;
        }
    }

    /** Whether app {@code uid} was on screen, as one of the BlockLog.BG_ values. */
    private int backgroundState(int uid) {
        ForegroundWatcher watcher = foregroundWatcher;
        if (watcher == null || uid < android.os.Process.FIRST_APPLICATION_UID) return BlockLog.BG_UNKNOWN;
        String onScreen = watcher.foregroundPackage();
        if (onScreen == null) return BlockLog.BG_UNKNOWN;          // no usage access
        if (onScreen.isEmpty()) return BlockLog.BG_BACKGROUND;      // screen off, or nothing on screen
        return Apps.forUid(this, uid).packages.contains(onScreen) ? BlockLog.BG_IN_USE : BlockLog.BG_BACKGROUND;
    }

    /** Asks the phone's normal DNS server and hands the answer back to the app. */
    private void forward(final DnsPacket.Query q) {
        ThreadPoolExecutor pool = upstreamPool;
        if (pool == null) return;
        pool.execute(() -> {
            byte[] reply = new byte[MTU];
            for (InetAddress server : upstreamServers) {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    if (!protect(socket)) return;   // would loop back into the VPN
                    socket.setSoTimeout(UPSTREAM_TIMEOUT_MS);
                    socket.send(new DatagramPacket(q.dnsPayload, q.dnsPayload.length, server, 53));
                    DatagramPacket packet = new DatagramPacket(reply, reply.length);
                    socket.receive(packet);
                    int n = packet.getLength();
                    if (!DnsPacket.isReplyTo(q, reply, n)) continue;
                    if (n + 28 > MTU) {
                        byte[] cut = DnsPacket.truncatedResponse(q, reply);
                        writeToTun(DnsPacket.buildResponsePacket(q, cut, cut.length));
                    } else {
                        writeToTun(DnsPacket.buildResponsePacket(q, reply, n));
                    }
                    return;
                } catch (IOException e) {
                    // Timed out or the network changed; try the next server.
                } finally {
                    if (socket != null) socket.close();
                }
            }
        });
    }

    private void writeToTun(byte[] packet) {
        FileOutputStream out = tunOut;
        if (out == null) return;
        synchronized (tunWriteLock) {
            try {
                out.write(packet);
            } catch (IOException e) {
                Log.w(TAG, "Could not deliver a DNS answer", e);
            }
        }
    }

    // ---- Which DNS server to pass ordinary lookups to ----

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        // This app is left out of its own VPN, so its "default network" is the real Wi-Fi or
        // mobile network, and that network's DNS servers are the ones the phone normally uses.
        Network active = cm.getActiveNetwork();
        updateUpstream(active == null ? null : cm.getLinkProperties(active));
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                updateUpstream(lp);
            }

            @Override
            public void onLost(Network network) {
                upstreamServers = fallbackServers();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't follow network changes", e);
            networkCallback = null;
        }
    }

    private void updateUpstream(LinkProperties lp) {
        List<InetAddress> servers = new ArrayList<>();
        if (lp != null) {
            for (InetAddress a : lp.getDnsServers()) {
                if (!DNS_ADDRESS.equals(a.getHostAddress())) servers.add(a);
            }
        }
        upstreamServers = servers.isEmpty() ? fallbackServers() : Collections.unmodifiableList(servers);
    }

    /** Used only if the phone's network reports no DNS server of its own. */
    private static List<InetAddress> fallbackServers() {
        try {
            return Collections.unmodifiableList(Arrays.asList(
                    InetAddress.getByAddress(new byte[] {1, 1, 1, 1}),
                    InetAddress.getByAddress(new byte[] {8, 8, 8, 8})));
        } catch (UnknownHostException e) {
            return Collections.emptyList();
        }
    }

    // ---- Notification ----

    private void goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            // Some phones refuse this when Android itself starts the VPN at boot.
            // Protection still works; only the status notification is missing.
            Log.w(TAG, "Could not show the status notification", e);
        }
    }

    private void scheduleNotificationUpdate() {
        if (notificationQueued.compareAndSet(false, true)) {
            mainHandler.postDelayed(() -> {
                notificationQueued.set(false);
                postNotification();
            }, NOTIFICATION_DELAY_MS);
        }
    }

    private void postNotification() {
        if (!running) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not update the notification", e);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShieldVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String text = String.format(Locale.getDefault(), "%,d tracker lookups blocked", BlockLog.get().blocked());
        BlockLog.Record latest = BlockLog.get().latest();
        if (latest != null) {
            text += " · latest: " + Apps.forUid(this, latest.uid).label + " → " + latest.owner;
        }
        PendingIntent openApps = PendingIntent.getActivity(this, 2,
                new Intent(this, AppsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Tracker protection is on")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_shield), "Which apps?", openApps).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_shield), "Turn off", stop).build())
                .build();
    }

    // ---- Helpers ----

    private static void skip(Builder builder, String pkg) {
        try {
            builder.addDisallowedApplication(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            // App was uninstalled; nothing to skip.
        }
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }
}
