#!/bin/sh
# End-to-end check of the installed CLI and HTTP API (after ./gradlew installDist).
set -eu
quarry=build/install/quarry/bin/quarry
port=${PORT:-8133}
dir=$(mktemp -d)
pid=""
trap '[ -n "$pid" ] && kill "$pid" 2>/dev/null; rm -rf "$dir"' EXIT

cat > "$dir/docs.jsonl" <<'JSON'
{"id": "bm25", "title": "Ranking with BM25", "text": "BM25 scores a document by how often the query terms occur in it, discounted by its length."}
{"id": "inverted", "title": "Inverted indexes", "text": "An inverted index maps every term to the list of documents that contain it."}
{"id": "skips", "title": "Skipping", "text": "Skip tables let a postings list jump ahead without decoding every block of the index."}
JSON

"$quarry" index "$dir/index" "$dir/docs.jsonl"
"$quarry" search "$dir/index" '"inverted index"' | tee "$dir/out"
grep -q ' inverted ' "$dir/out"
"$quarry" search "$dir/index" 'index -inverted' | tee "$dir/out"
grep -q ' skips ' "$dir/out"
if grep -q ' inverted ' "$dir/out"; then echo "an excluded document was returned"; exit 1; fi
"$quarry" check "$dir/index"

"$quarry" serve "$dir/index" --port "$port" &
pid=$!
api=http://localhost:$port
curl -fs --retry 30 --retry-delay 1 --retry-all-errors "$api/stats" | grep -q '"documents":3'
curl -fsS "$api/search?q=length" | grep -q '"id":"bm25"'
curl -fsS -X POST "$api/docs" -d '{"id": "merge", "title": "Merging", "text": "Small segments are merged into larger ones."}' | grep -q '"documents":4'
curl -fsS "$api/search?q=segment" | grep -q '"id":"merge"'
curl -fsS -X DELETE "$api/docs/merge" | grep -q '"deleted":true'
test "$(curl -s -o /dev/null -w '%{http_code}' "$api/docs/merge")" = 404
test "$(curl -s -o /dev/null -w '%{http_code}' "$api/search?q=%22unclosed")" = 400
echo "smoke test passed"
