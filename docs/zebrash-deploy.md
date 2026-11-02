# Zebrash binary — deployment runbook

Multiship renders carrier ZPL to PNG/PDF through either:

1. **`zebrash-cli`** — a native Go binary bundled inside the fat jar at
   `BOOT-INF/classes/native/{os-arch}/zebrash-cli[.exe]`. Preferred on
   production because **label content never leaves the VPC**: shipper +
   recipient addresses, tracking numbers, PO/DEPT, declared value, etc.
   stay on the pod.
2. **Labelary** — HTTP `POST api.labelary.com/v1/printers/.../labels/...`.
   Fallback when the native binary is missing (dev laptops without the
   bundled binary, CI runners). **Labelary receives label content on
   every render**; fine for sandbox + dev, not for production.

The default `-Dzebrash.skip=true` keeps the dev loop fast (no GitHub
download on every `mvn package`). Production MUST build with
`-Dzebrash.skip=false` and MUST flip `label.require-zebrash=true` so a
mis-packaged jar fails at startup rather than silently routing through
Labelary.

## Build

```bash
# Dev loop (fast, no bundling — Labelary covers the preview path)
mvn package -DskipTests

# Prod build — pulls the zebrash-cli-v* release from GitHub and bundles
# all four platform binaries into BOOT-INF/classes/native/
mvn package -DskipTests -Dzebrash.skip=false
```

Confirm the binaries landed:

```bash
unzip -l target/backend-0.0.1-SNAPSHOT.jar | grep native/.*zebrash
# BOOT-INF/classes/native/darwin-arm64/zebrash-cli
# BOOT-INF/classes/native/linux-amd64/zebrash-cli
# BOOT-INF/classes/native/linux-arm64/zebrash-cli
# BOOT-INF/classes/native/windows-amd64/zebrash-cli.exe
```

## Runtime config

`application.properties` (or env overrides):

| Key | Dev | Prod | What it does |
|-----|-----|------|-----|
| `label.render-carrier-zpl` | `true` | `true` | Master switch for the ZPL→PNG preview path. Off = /label/preview.png 404s. |
| `label.require-zebrash` | `false` | **`true`** | Fail-closed knob. When true, startup throws if the binary isn't on the classpath. Prevents silent Labelary fallback. |
| `label.labelary.enabled` | `true` | **`false`** | Explicitly disables the Labelary fallback. With require-zebrash=true the fallback is unreachable anyway, but belt-and-braces. |

Example prod overlay:

```properties
label.render-carrier-zpl=${LABEL_RENDER_CARRIER_ZPL:true}
label.require-zebrash=${LABEL_REQUIRE_ZEBRASH:true}
label.labelary.enabled=${LABEL_LABELARY_ENABLED:false}
```

## Boot-time verification

A healthy prod boot emits exactly one of these at INFO:

```
ZebrashRenderer ready at /tmp/zebrash-cli-XXXX (timeout=5s) — label content stays in the VPC.
```

If the binary is missing with `label.require-zebrash=false`:

```
WARN ZebrashRenderer not available at startup: zebrash-cli binary not found ...
     LabelaryRenderer will serve the preview path (label content leaves the VPC).
     Build with -Dzebrash.skip=false ...
```

If the binary is missing with `label.require-zebrash=true` (prod):

```
IllegalStateException: ZebrashRenderer required but binary missing: ...
     — rebuild with -Dzebrash.skip=false or flip label.require-zebrash=false
     to allow the Labelary fallback (see docs/zebrash-deploy.md).
```

The pod fails to start — the ops dashboard shows CrashLoopBackOff
immediately, not after the first user tries to preview a label.

## Rollback

Something broke after flipping require-zebrash on in prod? Two
unwinds, in increasing scope:

1. **Env flip** (zero deploy): set `LABEL_REQUIRE_ZEBRASH=false` and
   `LABEL_LABELARY_ENABLED=true`, bounce the pod. Preview path routes
   through Labelary. Label content WILL leave the VPC until the real
   fix lands — flip back off during a planned maintenance window.
2. **Build revert**: ship the previous jar (which bundled binaries
   that worked). The pom.xml wiring is pure build-time; app code has
   no release coupling.

## Release pipeline

The `zebrash-cli` Go source lives at `native/zebrash-cli/`. CI job
`.github/workflows/native-zebrash.yml` cross-compiles for 4 platforms
and publishes a GitHub release on `zebrash-cli-v*` tags.

To cut a new release:

```bash
# Verify the Go changes build locally
make -C native/zebrash-cli all

# Tag + push
git tag zebrash-cli-vX.Y.Z
git push origin zebrash-cli-vX.Y.Z
```

Then bump `zebrash.release.tag` in `backend/pom.xml` to pick up the new
version on the next build. The old release stays reachable at
`https://github.com/.../releases/tag/zebrash-cli-vX.Y.Z` for
rollback.

## FAQ

**Q: Why ship four platform binaries? We only run on Linux.**
A: Developer laptops run macOS (darwin-arm64) and Windows
(windows-amd64) and `mvn spring-boot:run` picks up the right binary at
startup. Shipping all four means one jar is a single artifact for every
environment.

**Q: Can we strip Labelary entirely?**
A: Yes once every environment that boots the backend ships the native
binary. Keep it for now so sandbox/CI runs that skip the bundle still
have a path.

**Q: What does Labelary see when it's the fallback?**
A: The raw ZPL bytes — which contain the shipper's full address, the
recipient's full address, every tracking number, every PO/DEPT ref,
weights, and declared value. The response is a PNG/PDF of the printed
label. Treat Labelary as a non-VPC vendor with full visibility into
every shipment that renders through it.
