// Command zebrash-cli reads ZPL bytes from stdin and writes a PNG to
// stdout at a caller-specified size and density.
//
// Invocation (from Spring Boot ZebrashRenderer):
//
//	zebrash-cli --width 4 --height 6 --dpmm 8 < label.zpl > label.png
//
// Exit codes:
//
//	0 — PNG written to stdout
//	1 — invalid CLI flags
//	2 — ZPL parse failure (parser error text on stderr)
//	3 — draw / encode failure (details on stderr)
//
// Kept intentionally small (~60 LoC) so the Java caller can trust the
// binary contract and the maintenance surface is minimal. Upstream
// ingridhq/zebrash provides the parser + drawer; we're only wiring
// I/O and one geometry conversion.
package main

import (
	"flag"
	"fmt"
	"image/png"
	"io"
	"os"

	"github.com/ingridhq/zebrash"
	"github.com/ingridhq/zebrash/drawers"
)

func main() {
	widthIn := flag.Float64("width", 4.0, "label width in inches")
	heightIn := flag.Float64("height", 6.0, "label height in inches")
	dpmm := flag.Int("dpmm", 8, "print density (dots per mm): 6, 8, 12, or 24")
	flag.Parse()

	if *widthIn <= 0 || *heightIn <= 0 || *dpmm <= 0 {
		fmt.Fprintln(os.Stderr, "width, height, and dpmm must all be positive")
		os.Exit(1)
	}

	zpl, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read stdin: %v\n", err)
		os.Exit(1)
	}

	parser := zebrash.NewParser()
	labels, err := parser.Parse(zpl)
	if err != nil {
		fmt.Fprintf(os.Stderr, "zpl parse: %v\n", err)
		os.Exit(2)
	}
	if len(labels) == 0 {
		fmt.Fprintln(os.Stderr, "zpl parse: no ^XA…^XZ blocks found")
		os.Exit(2)
	}

	// dpmm × mm/in = dots per inch; times physical inches = pixel dims.
	// Zebra convention: 8 dpmm ≈ 203 dpi → 4×6" = 812×1218 px.
	const mmPerInch = 25.4
	widthPx := int(*widthIn * mmPerInch * float64(*dpmm))
	heightPx := int(*heightIn * mmPerInch * float64(*dpmm))

	drawer := drawers.NewDrawer()
	// Render only the first label — carriers return one ^XA…^XZ per
	// package; multi-package labels are handled at the Java layer by
	// concatenating multiple renders into a multi-page PDF.
	img, err := drawer.DrawLabelAsImage(labels[0], drawers.DrawerOptions{
		LabelWidthPixels:  widthPx,
		LabelHeightPixels: heightPx,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "draw: %v\n", err)
		os.Exit(3)
	}

	if err := png.Encode(os.Stdout, img); err != nil {
		fmt.Fprintf(os.Stderr, "png encode: %v\n", err)
		os.Exit(3)
	}
}
