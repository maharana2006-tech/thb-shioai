# zebrash-cli

Thin Go wrapper around [`ingridhq/zebrash`](https://github.com/ingridhq/zebrash) that
reads ZPL from stdin and writes a PNG to stdout. Consumed by the Spring Boot
backend's `ZebrashRenderer` via `ProcessBuilder`.

## Why this exists

The Java backend renders `/label/{orderNo}` as a JSX facsimile that inevitably
diverges from the carrier's actual thermal-printed label (see
`memory/project_label_preview_audit.md`). Rendering the carrier's real ZPL bytes
to PNG server-side eliminates the divergence for good, but no pure-Java ZPL
renderer covers the FedEx feature set. `zebrash` is the best OSS Go option.
We ship it as a per-platform native binary inside the backend JAR, extract at
runtime, and invoke via `ProcessBuilder` — same `sqlite-jdbc`-style pattern.

## Building

Local (needs Go 1.25+):

```
cd native/zebrash-cli
make all              # produces dist/{os}-{arch}/zebrash-cli[.exe] for 4 platforms
```

Then drop into the JAR resources:

```
mkdir -p ../../backend/src/main/resources/native
cp -r dist/* ../../backend/src/main/resources/native/
```

CI: `.github/workflows/native-zebrash.yml` runs the same matrix on push and
attaches artifacts to a release, which the Maven `download-maven-plugin` picks
up at backend build time.

## Contract

```
zebrash-cli --width 4 --height 6 --dpmm 8 < label.zpl > label.png
```

| Flag       | Default | Notes                                             |
|------------|---------|---------------------------------------------------|
| `--width`  | `4.0`   | Label width in inches                             |
| `--height` | `6.0`   | Label height in inches                            |
| `--dpmm`   | `8`     | Print density: 6, 8, 12, or 24                    |

Exit codes:

| Code | Meaning                                                  |
|------|----------------------------------------------------------|
| 0    | PNG written to stdout                                    |
| 1    | Invalid flags / stdin read failed                        |
| 2    | ZPL parse failure (details on stderr)                    |
| 3    | Draw or PNG encode failure                               |

## Upstream risk

`ingridhq/zebrash` maintainer stepped back March 2026. If we hit a rendering
gap, options are (a) pin an earlier working commit, (b) fork under
`thb-shioai/zebrash`, or (c) fall back to the paid Labelary self-hosted engine.
Not urgent — the library covers Code 128, PDF417, `^GB`, `^A0`/`^AdN`, `^FH`,
`^CI13` — the full FedEx feature set our labels use today.
