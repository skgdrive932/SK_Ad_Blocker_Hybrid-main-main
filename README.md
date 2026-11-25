# SK Ad Blocker — DNS Filtering Edition

A Kotlin Android local-VPN project by **Sk kaushal**. The UI is dark navy/mint and includes explicit Start/Stop controls.

## Fixes in v1.2.1

- **Protection no longer switches itself off right after Start.** The VPN file descriptor is now created with `setBlocking(true)`; before, a non-blocking `read()` returned 0 when no packet was waiting and the packet loop treated that as "stop".
- **Crash fix:** the TUN streams are no longer closed with `use {}`, which closed the descriptor twice (fdsan abort on Android 10+).
- **Faster DNS:** lookups are handled by a small thread pool, so one slow/timed-out lookup no longer freezes DNS for every app.
- **Cleaner DNS replies:** blocked/SERVFAIL answers are trimmed to the question section (no stray EDNS bytes).
- Start can be pressed again right after Stop (stop flag is reset).
- Notification: tapping opens the app, and the Stop action now has an icon.
- Added launcher icon, `.gitignore`, and moved CI to `gradle/actions/setup-gradle@v4` (the old `gradle-build-action@v3` is deprecated).

## Reliability update (v1.2.0)

Some mobile carriers and Wi-Fi networks block outbound UDP/53. The DNS proxy now falls back to HTTPS-based DNS using protected sockets and TLS hostname verification. This is intended to prevent a DNS-only VPN from making every app appear offline when plain DNS is unavailable. It still requires access to at least one upstream resolver.

## Key safety fix

The earlier scaffold routed all IPv4 traffic (`0.0.0.0/0`) into a tunnel without forwarding packets, which could cut off internet access. This version routes **only the virtual DNS endpoint** (`10.8.0.1/32`) into the VPN. Ordinary app/web traffic does not enter the TUN interface and continues over the phone's normal Wi-Fi/mobile-data network. DNS queries handled by the virtual endpoint are filtered locally. Allowed queries first try protected UDP DNS to Cloudflare/Google, then fall back to DNS-over-HTTPS (Cloudflare, then Google) when UDP/53 is blocked.

## What it can and cannot block

- Blocks requests for a small built-in list of common ad/tracker domains through system DNS.
- Can work for apps and browsers that use Android's system DNS.
- It cannot guarantee removal of every ad. Ads served from the same domain as content, sponsored posts, and apps using encrypted DNS (DoH/DoT) may bypass this DNS filter.
- This build does not inspect HTTPS payloads, install certificates, or intercept credentials.
- The local TUN parser handles IPv4 UDP DNS. DNS-over-HTTPS is used only for upstream forwarding; apps that implement their own encrypted DNS may bypass filtering. If all upstream DNS transports are blocked, name lookups can still fail, but ordinary non-DNS packets are not routed into the TUN.

## Controls

- Start asks for Android's VPN permission.
- Start and Stop are separate controls. Stop sends an explicit service stop action, closes the TUN file descriptor, interrupts the packet worker, removes the foreground notification, and stops the service. Stop remains available during startup so a pending start can be cancelled.
- The service is not sticky, so Android does not automatically restart it after it is stopped.

## Build APK in GitHub Actions

1. Push this repository to GitHub.
2. Open **Actions → Android Build**.
3. Run the workflow or push a commit.
4. Download the `SK-Ad-Blocker-debug` artifact from the completed workflow.

Build uses JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, and matching Java/Kotlin JVM target 17. The checked-in `gradlew` is a lightweight placeholder; CI uses the Gradle action's managed Gradle distribution.

## Developer / About

- Name: Sk kaushal
- Email: skgdrive932@gmail.com
- Contact: +919779371866

## Privacy

No analytics or remote VPN server is configured. DNS requests for allowed domains are sent to public resolvers (Cloudflare, then Google fallback).

## License

See [`LICENSE`](LICENSE). This is a personal-use license, not an OSI-approved open-source license.


## Test checklist before daily use

1. Install the debug APK from GitHub Actions and grant the Android VPN permission.
2. With protection OFF, verify a webpage loads over Wi-Fi and mobile data.
3. Turn protection ON; verify a normal website still loads and an ad-domain lookup is blocked.
4. Press Stop; verify the VPN key/status disappears, the persistent notification is removed, and websites still load.
5. Repeat on both Wi-Fi and mobile data. Android OEM battery/background policies and Private DNS settings can affect results.

This source package has passed archive/XML/static source checks only. A real Android build and device connectivity test must still be run by GitHub Actions and on a phone; no source-only check can certify every device/network combination.
