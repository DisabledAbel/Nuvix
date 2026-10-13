# tools

Developer utilities. Not part of the app, not shipped in the APK.

## stalker_mock.py

A tiny, dependency-free mock **Stalker / Ministra portal** for testing OpenTV's Stalker
support without a real provider line. It speaks the slice of the protocol the app's client
calls (`handshake`, `get_genres`, `get_all_channels`, `create_link`) and hands back a few
channels pointing at **free, legal test streams** (Big Buck Bunny, Apple BipBop, Sintel), so
you can exercise the whole path — add source → handshake → channel list → play —
deterministically, and actually see video.

```sh
python3 tools/stalker_mock.py          # listens on 0.0.0.0:8080
python3 tools/stalker_mock.py 9000     # ...or a port you pick
```

It prints the exact values to enter in OpenTV (**Add source → Stalker portal**). Point the box
at the machine running it; the control API is served locally, the video comes from public CDNs.
Every request is logged, so you can watch the handshake and each call land — handy when a real
portal misbehaves and you want to mimic it. See the docstring at the top of the file for details.

Not affiliated with Infomir. No copyrighted content is served or proxied.
