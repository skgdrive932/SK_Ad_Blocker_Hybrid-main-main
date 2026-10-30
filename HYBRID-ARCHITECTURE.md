# Architecture and limitations

This version implements a **DNS-only local VPN**. It does not use a full-device default route. The only route directed to the TUN is `10.8.0.1/32`, the virtual DNS address configured on the VPN interface.

The service reads IPv4 UDP DNS packets, checks the queried domain against a small built-in list, returns NXDOMAIN for matches, and forwards allowed queries to public DNS resolvers through a socket protected from the VPN. Non-DNS IP traffic is not sent to the TUN and therefore does not require a packet-forwarding engine.

Limitations: no IPv6 DNS packet processing, no TCP DNS, no DoH/DoT filtering, no dynamic blocklist updater, and no guarantee against all in-app ads. A production release should add tests for packet parsing, resolver failures, Android versions, and real devices.

An HTTPS test proxy is not included or silently enabled. HTTPS interception requires explicit opt-in and test-only trust configuration; this project does not bypass certificate pinning.


## DNS upstream reliability update (1.0.1)

The service first attempts protected UDP/53 to public resolvers, then uses DNS-over-HTTPS over a protected TLS socket to Cloudflare and Google. TLS endpoint identification is enabled. The VPN routes only the virtual DNS endpoint; it does not claim to forward arbitrary internet traffic. DNS-only filtering cannot block every in-app ad, and upstream DNS must remain reachable for new domain lookups.
