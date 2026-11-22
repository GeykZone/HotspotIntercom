# Alpha-Connect

*Powered by Geykie*

Alpha-Connect is an Android intercom that works over a phone's own Wi-Fi hotspot. No internet, router or SIM data is needed. One phone hosts the hotspot, and up to 10 other phones join it to talk.

## Features

- Works offline over a phone hotspot (UDP voice over the local network)
- Up to 10 phones can join one host
- Push-to-talk: hold the big button and speak
- Hands-free mode: your voice is sent only while you speak
- Loudspeaker or earpiece output, switchable at any time
- Works with wired earphones that have a microphone
- Keeps running with the screen off
- Dark neumorphic interface

## Download and install

1. Open the [latest release](../../releases/latest) and download the `.apk` file.
2. Open the file on your phone.
3. If Android asks, allow **Install unknown apps** for your browser or file manager.
4. If Play Protect shows a warning, tap **Install anyway**.
5. Open Alpha-Connect and allow the microphone and notification permissions.

Requires Android 8.0 (API 26) or newer.

## What you need

- Two or more Android phones with Alpha-Connect installed
- One phone to act as the **host**. It turns on its hotspot and shares it with the others
- The host phone should stay plugged in, because it carries the most traffic and battery drain
- Optional: earphones (wired earphones with a mic work best)

## Connecting the devices

### On the host phone

1. Turn on the **Mobile Hotspot**.
   - Samsung: Settings > Connections > Mobile Hotspot and Tethering
   - Most other phones: Settings > Network & internet > Hotspot & tethering
2. Note the hotspot name and password.
3. Open Alpha-Connect and allow the microphone and notification permissions.
4. Tap **HOST**. The status line should read: `Hosting. 0/10 phones connected.`

### On every other phone

1. Keep Wi-Fi on. If joining fails, turn **off mobile data** (see [Troubleshooting](#troubleshooting)).
2. Go to Wi-Fi settings and connect to the host's hotspot with its password. A message saying there is no internet is normal.
3. Open Alpha-Connect and allow the permissions.
4. Check the **Host IP address** box. The app fills in the gateway address automatically. If it looks wrong, see [Finding the gateway address](#finding-the-gateway-address-of-the-host-phone).
5. Tap **JOIN**. After a few seconds the status turns green and says `Joined`, and the host shows `1/10 phones connected`.

### Talking

- **Push-to-talk:** hold the big round button and speak. It glows red while you talk.
- **Hands-free:** turn on the Hands-free switch. The button glows cyan and your voice is sent only while you speak. You can still hold the button to force talking.
- **Loudspeaker switch:** on for the loudspeaker, off for the earpiece. When earphones are connected, the sound and mic follow them.
- Use the phone's volume buttons to change the volume while the app is open.

## Finding the gateway address of the host phone

The joining phones need the host phone's address on the hotspot. This is called the **gateway** (or router) address. It usually looks like `192.168.x.1` and nearly always ends in `.1`. Try the methods in order.

### Method 1: Let the app fill it in

Connect the joining phone to the host's hotspot **before** opening the app. The Host IP address box fills in the gateway by itself.

### Method 2: Check the joining phone's Wi-Fi settings

1. Connect to the host's hotspot.
2. Open Settings > Connections (or Network & internet) > Wi-Fi.
3. Tap the **gear icon** next to the hotspot name (or tap the network name).
4. Tap **View more**, **Advanced**, or **Network details**, and scroll down.
5. Read the value labeled **Gateway** or **Router**. Type it into the Host IP address box.

Do not use the value labeled **IP address**. That is the joining phone's own address on the hotspot, not the host's.

### Method 3: Use a computer on the same hotspot

1. Connect a Windows computer to the host's hotspot.
2. Open Command Prompt and type `ipconfig`.
3. Find the line **Default Gateway** under the Wi-Fi adapter. That is the host's address.

On a Mac, open System Settings > Wi-Fi > Details > TCP/IP and read **Router**.

### Method 4: Try the common defaults

If you still cannot find it, try these on the joining phone, one at a time, tapping **STOP** and then **JOIN** after each:

- `192.168.43.1` (older Android phones)
- `192.168.x.1`, where x is the number your hotspot uses, such as `192.168.123.1`, `192.168.1.1` or `192.168.0.1`. The first three numbers should match the joining phone's own address.

### If the address looks wrong

- An address that does not end in `.1` and starts with `10.` or `100.` is often the phone's mobile data address, not the hotspot. Turn off mobile data and check again.
- Make sure the joining phone is on the host's hotspot and not on a regular Wi-Fi network.

## Troubleshooting

- **Stuck on "Connecting...":** turn off mobile data on the joining phone, check the gateway address, and make sure the host phone tapped **HOST** (not **JOIN**).
- **"Host is full":** the host accepts 10 phones, so another phone must leave first. Also check the hotspot's own device limit, which on some phones is lower than 10.
- **Joined but no sound:** raise the call volume, check that the Loudspeaker switch is on, and check the microphone permission under Settings > Apps > Alpha-Connect > Permissions.
- **Squealing or echo:** the speaker is feeding into the microphone. Move the phones apart or use earphones.
- **Hands-free sends background noise or misses quiet voices:** the developer can tune this with the `MIN_VOICE_RMS` value in `IntercomEngine.kt`.
- **Phones cannot see each other:** turn off client isolation (also called AP isolation) in the hotspot settings if the phone has that option.

## Build from source

1. Install [Android Studio](https://developer.android.com/studio).
2. Clone or download this repository and open it in Android Studio.
3. Let Gradle sync finish.
4. Connect a phone with USB debugging on, then click **Run**.

To build an APK, go to **Build > Generate Signed App Bundle or APK > APK**.

Never commit your keystore file or its passwords to this repository.

## Credits

Alpha-Connect, powered by Geykie.
