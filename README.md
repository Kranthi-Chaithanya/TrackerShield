# TrackerShield

TrackerShield is a small Android app that stops the apps on your phone from sending your data to
tracking and advertising companies such as Google Firebase Analytics, Facebook SDK, AppsFlyer,
CleverTap, MoEngage, AppLovin and the ad servers of Xiaomi, OPPO, vivo and Samsung.

It works for every app on the phone at once and does not need root.

## How it works

Before an app can send data to a server like `app-measurement.com`, it has to look up that
server's address (a "DNS lookup"). TrackerShield sets up a VPN that stays on your phone and
only carries these lookups:

* A lookup for one of the 181 tracker domains on its list gets the answer "no such address",
  so the app can't send the data.
* All other lookups go to your normal DNS server and are answered as usual.
* Your other internet traffic does not pass through TrackerShield, so it isn't slowed down.
* Nothing is sent to an outside VPN server or company, and the app has no analytics of its own.

The screen shows a live list of the trackers each lookup tried to reach and which companies were
blocked most.

## Which apps are tracking you

Tap **Which apps are tracking me?** to see the apps ranked by how much they track you. Tap an app
to see the full details:

* **Tracking attempts on this phone:** every tracking company the app tried to send data to, which
  addresses, how many times, and when. Naming the app needs Android 10 or newer.
* **While you weren't using it:** how many attempts happened in the background or with the screen
  off. Turn on **Usage access** for TrackerShield when the app asks (Android Settings > Apps >
  Special app access > Usage access). Without it, this part is left out.
* **Tracking code built into the app:** TrackerShield reads the class names in each installed app's
  code and finds 90 known tracking libraries, such as AppsFlyer, Firebase Analytics, the Facebook
  SDK, CleverTap, MoEngage and AppLovin. This shows what an app is able to do, even before it
  does it.
* **What it can access:** location, contacts, microphone, camera, texts, call history and other
  sensitive permissions you have given it, with a button to change them or uninstall the app.

The history is saved on your phone and kept when you restart. **Clear list and counters** in the
⋮ menu deletes it.

## Get the app onto your phone

You have to build the source code into an installable `.apk` file once. Choose one of these:

### Option A: let GitHub build it (no software to install)

1. Create a free account at github.com and make a new repository (it can be private).
2. Upload all the files in this folder to it. Keep the folder layout, including the hidden
   `.github` folder. ("Add file" > "Upload files" works; drag the whole folder in.)
3. Open the **Actions** tab. The "Build APK" job starts by itself and takes about 3–5 minutes.
   If the Actions tab shows nothing, the hidden `.github` folder didn't upload. Use
   **Add file > Create new file**, type the name `.github/workflows/build-apk.yml`, paste in the
   contents of that file from this folder, and commit.
4. When it shows a green tick, open it and download **TrackerShield-apk** under *Artifacts*.
   Unzip it to get `app-debug.apk`.
5. Copy `app-debug.apk` to your phone and open it. Android will ask you to allow installing
   apps from that source. Allow it, then tap **Install**.

### Option B: Android Studio (on a computer)

1. Install Android Studio from developer.android.com/studio.
2. Choose **File > Open** and select this folder. Wait for it to finish setting up.
3. Connect your phone with USB debugging turned on and press the green **Run** button.
   You can also use **Build > Build App Bundle(s) / APK(s) > Build APK(s)** and copy the APK to the phone.

It needs Android 8.0 or newer.

## Using it

1. Open TrackerShield and tap **Turn protection on**. Android asks if TrackerShield may set up a
   VPN connection. Tap **OK**. A key icon appears in the status bar.
2. Use your phone as normal. Blocked trackers appear in the list.
3. To start protection automatically after a restart, go to Android **Settings > Network &
   internet > VPN** (on Samsung: **Connections > More connection settings > VPN**). Tap the gear
   next to TrackerShield and turn on **Always-on VPN**.
   **Do not** turn on "Block connections without VPN". TrackerShield only carries DNS lookups,
   so that option would cut off the internet. The app shows a warning if it is on.

### Phone settings that affect it

* **Private DNS** must be **Automatic** or **Off**. If it is set to a provider name (for example
  `dns.google`), apps send lookups straight to that provider and TrackerShield can't see them.
  The app warns you about this too.
* Only one VPN app can run at a time. Turning on another VPN switches TrackerShield off.

### If an app stops working

Some apps refuse to work when their trackers are blocked (most often because of
`graph.facebook.com`, which the Facebook app itself also uses).

* **Tap the domain in the list** to allow just that one, or
* tap **Apps to skip** and tick the app so TrackerShield leaves it alone completely.

The menu (⋮) has **Block everything I allowed**, to undo all the domains you allowed.

### Block more

Tap **Block a site** to block any extra domain in every app, for example a news site's ad server.
To change the built-in list, edit `app/src/main/assets/trackers.txt` (one `domain|Company` per
line) and rebuild.

## Limits

* It blocks by name, so it can't stop an app that connects to a hard-coded IP address or uses its
  own encrypted DNS. Few apps do this.
* It stops data from being **sent**. It doesn't change what an app can read on the phone. For
  that, also check **Settings > Privacy > Permission manager**, and delete your advertising ID
  under **Settings > Privacy > Ads** (or **Google > Ads**).
* Push notifications, logins, app stores and video servers are deliberately not on the list.

## Project layout

```
app/src/main/java/com/trackershield/app/
  ShieldVpnService.java   the local VPN: reads DNS lookups, names the app, blocks or forwards them
  DnsPacket.java          reads and builds IPv4/UDP/DNS packets
  Blocklist.java          tracker list matching, your allowed and blocked domains
  BlockLog.java           per-app tracking history, saved to a file
  DexScanner.java         finds tracking libraries inside an app's code
  AppScanner.java         checks installed apps (only new or updated ones after the first time)
  ForegroundWatcher.java  tells whether an app was on screen (with Usage access)
  Apps.java               app names, icons and permissions
  MainActivity.java       main screen
  AppsActivity.java       "Which apps are tracking me?"
  AppDetailActivity.java  one app's full report
app/src/main/assets/trackers.txt         tracker domains
app/src/main/assets/sdk_signatures.txt   tracking libraries
testapp/                           "Tracker Demo", used only by the automated test
.github/workflows/build-apk.yml    builds the APK, tests it on an emulator, publishes the release
```

The app uses only built-in Android features and no outside libraries.
