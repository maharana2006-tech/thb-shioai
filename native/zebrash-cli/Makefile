# Cross-compile zebrash-cli for the 4 platforms the Java backend supports.
# Outputs land in dist/{os}-{arch}/zebrash-cli[.exe] — that's the exact
# directory shape src/main/resources/native/ expects, so a simple
# `cp -r dist/* backend/src/main/resources/native/` finalises the drop.
#
# Run `make all` locally (Go 1.25+ required) or let the GitHub Actions
# matrix in .github/workflows/native-zebrash.yml do it on push.

BIN := zebrash-cli
DIST := dist

.PHONY: all clean linux-amd64 linux-arm64 darwin-arm64 windows-amd64

all: linux-amd64 linux-arm64 darwin-arm64 windows-amd64

linux-amd64:
	@mkdir -p $(DIST)/linux-amd64
	GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o $(DIST)/linux-amd64/$(BIN) .

linux-arm64:
	@mkdir -p $(DIST)/linux-arm64
	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o $(DIST)/linux-arm64/$(BIN) .

darwin-arm64:
	@mkdir -p $(DIST)/darwin-arm64
	GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o $(DIST)/darwin-arm64/$(BIN) .

windows-amd64:
	@mkdir -p $(DIST)/windows-amd64
	GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o $(DIST)/windows-amd64/$(BIN).exe .

clean:
	rm -rf $(DIST)
