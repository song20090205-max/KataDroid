#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
MODEL_DIR="${MODEL_DIR:-models}"
MODEL_NAME="${MODEL_NAME:-kata1-tf3-b11c768-s11003M-d5973M-7gres}"
NETWORKS_PAGE="${NETWORKS_PAGE:-https://katagotraining.org/networks/}"
mkdir -p "$MODEL_DIR"
TARGET="$MODEL_DIR/model.bin.gz"
TMP="$TARGET.part"
PAGE_TMP="$(mktemp)"
trap 'rm -f "$TMP" "$PAGE_TMP"' EXIT

if [[ -n "${MODEL_URL:-}" ]]; then
  MODEL_URL="$MODEL_URL"
else
  echo "Resolving official network download URL for: $MODEL_NAME"
  curl --fail --location --retry 3 --retry-all-errors --output "$PAGE_TMP" "$NETWORKS_PAGE"
  MODEL_URL="$(python3 - "$PAGE_TMP" "$MODEL_NAME" "$NETWORKS_PAGE" <<'PY'
import sys
from html.parser import HTMLParser
from urllib.parse import urljoin

page_path, target, base = sys.argv[1:]
class NetworkRows(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_row = False
        self.row_text = []
        self.links = []
        self.matches = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "tr":
            self.in_row, self.row_text, self.links = True, [], []
        elif self.in_row and tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
    def handle_data(self, data):
        if self.in_row:
            self.row_text.append(data)
    def handle_endtag(self, tag):
        if tag == "tr" and self.in_row:
            text = " ".join(" ".join(self.row_text).split())
            if target in text:
                self.matches.append((text, self.links[:]))
            self.in_row = False

parser = NetworkRows()
with open(page_path, encoding="utf-8") as f:
    parser.feed(f.read())
if len(parser.matches) != 1:
    raise SystemExit(f"Expected exactly one official row for {target}, found {len(parser.matches)}")
links = parser.matches[0][1]
if not links:
    raise SystemExit(f"No download link found for {target}")
print(urljoin(base, links[0]))
PY
)"
fi

echo "Downloading official KataGo network: $MODEL_NAME"
curl --fail --location --retry 3 --retry-all-errors --output "$TMP" "$MODEL_URL"
gzip -t "$TMP"
mv "$TMP" "$TARGET"
trap - EXIT
rm -f "$PAGE_TMP"
echo "Model installed: $TARGET"
echo "Selected network: $MODEL_NAME"
sha256sum "$TARGET"
