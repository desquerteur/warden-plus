# Warden+ and Tailscale (or any other VPN)

**Short version:** Android runs only **one VPN at a time**. Warden+'s website filter *is* a VPN
(it stays on your phone and only looks at DNS). When Tailscale – or any other VPN app – is
switched on, Android stops Warden's filter. App blocking (accessibility) keeps working; website
blocking does not. No app can change this rule of Android.

## What Warden+ does about it (VPN bypass protection)

Settings → Protection → **VPN bypass protection**:

| Option | What it does | Limits |
|---|---|---|
| Block VPN apps | Category "VPN apps": every app with a VPN service gets the block screen when opened, also ones installed later. | Android's quick-settings tile and the VPN page in Settings can still switch a VPN on. |
| Trusted VPN | Tap **Trust** next to an app you need (Warden+ suggests Tailscale). It may run. Like every loosening this goes through your Block protection (PIN, delay, lock). | While it runs, Warden's filter can't. |
| Block VPN & proxy sites | Adds hagezi's "Encrypted DNS/VPN/Tor/Proxy Bypass" list (GPL-3.0, [github.com/hagezi/dns-blocklists](https://github.com/hagezi/dns-blocklists)). VPN, proxy and DoH websites can't be reached, so no new VPN gets downloaded or signed into. GitHub (Warden's updates) always stays reachable. | It also lists `tailscale.com`: logging in to Tailscale needs Warden's filter to be off, which it is while Tailscale runs. |
| "Another VPN took over" alert | Loud notification the moment it happens, with **Restore** (takes the VPN slot back) and **VPN settings**. Counted in Protection and Usage. Names the app when Warden+ can tell (Always-on VPN, or a VPN app you just had open). | Android doesn't tell other apps who owns a VPN, so sometimes it just says "Another VPN". |
| Browser mode | While the filter isn't running, Warden+ reads the address bar of Chrome, Samsung Internet, Firefox, Edge, Brave, Opera, Vivaldi, DuckDuckGo and Kiwi. A blocked website gets the block screen. | Only those browsers and only what their address bar shows. Apps with their own built-in browser (Instagram, TikTok …) aren't covered. Needs accessibility. |
| Private DNS warning | Android's "Private DNS" set to a provider (e.g. `dns.google`) sends DNS encrypted past the filter. Home and Protection warn and open the settings. Set it to **Automatic**. | – |
| Device owner (expert) | "Switch off VPN apps", "Lock VPN settings", "Always-on website filter", "Lock Private DNS": Android itself enforces it. See [DEVICE_OWNER.md](DEVICE_OWNER.md). The emergency exit lifts everything. | Needs a computer once. |

## Keeping website blocking *with* Tailscale on: use Tailscale's DNS

If you need Tailscale on all the time, let **Tailscale** do the DNS filtering instead of Warden+.
Then the filter runs inside Tailscale's VPN, and Android's one-VPN rule doesn't matter.

### Option 1: NextDNS (easiest, no own server)

1. Make a free account at [nextdns.io](https://nextdns.io) and create a configuration.
2. **Privacy → Blocklists:** add the lists you want (e.g. "HaGeZi – Multi Pro", "OISD").
   **Denylist:** add the same sites you block in Warden+ (e.g. `instagram.com`, `youtube.com`).
   **Security:** turn on "Block bypass methods" (stops other DoH/VPN tricks).
3. In the [Tailscale admin console](https://login.tailscale.com/admin/dns) → **DNS**:
   *Global nameservers* → **Add nameserver → NextDNS** and paste your NextDNS ID.
   Switch on **Override DNS servers** (in older versions: "Override local DNS").
4. In the Tailscale app on the phone: ⋮ → **Use Tailscale DNS settings** must be on.
5. Check: with Tailscale on, open a site from your denylist – it must not load.

Lock it: give the Tailscale admin login and the NextDNS login to a friend, or set a long random
password you don't keep on the phone – otherwise the filter is one click away.

### Option 2: Pi-hole / AdGuard Home on your own computer

1. Install Pi-hole (or AdGuard Home) on a computer or Raspberry Pi that is always on, and install
   Tailscale on it too.
2. Add your blocklists in Pi-hole (Adlists, e.g. the hagezi lists) and your own sites (Domains → Deny).
3. Tailscale admin console → **DNS** → *Global nameservers* → **Add nameserver → Custom** → the
   computer's Tailscale IP (100.x.y.z). Switch on **Override DNS servers**.
4. On the phone: Tailscale app → **Use Tailscale DNS settings** on.

### What this can't do

- Warden's schedules, focus sessions and "Block until" don't control NextDNS / Pi-hole – those
  block all the time (NextDNS has its own schedules under "Parental control").
- App blocking still comes from Warden+ (accessibility) and keeps working either way.
- If Tailscale is switched off, Warden's own filter can take over again: tap **Restore** in the
  notification or "Try again" on Home.
