#!/bin/sh
# Download BEIR datasets into data/<name>, e.g.
#   scripts/download_beir.sh scifact nfcorpus fiqa
set -eu
base=https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets
mkdir -p data
for name in "$@"; do
    if [ -f "data/$name/corpus.jsonl" ]; then
        echo "data/$name: already downloaded"
        continue
    fi
    curl -fsSL --retry 3 -o "data/$name.zip" "$base/$name.zip"
    unzip -q -o "data/$name.zip" -d data
    rm "data/$name.zip"
    echo "data/$name: $(wc -l < "data/$name/corpus.jsonl") documents"
done
