#!/bin/bash

set -eo pipefail

if [[ "$1" == "check" ]]; then
  clojure -Sdeps '{:deps {dev.weavejester/cljfmt {:mvn/version "0.16.6"}}}' -M -m cljfmt.main check src test
else
  clojure -Sdeps '{:deps {dev.weavejester/cljfmt {:mvn/version "0.16.6"}}}' -M -m cljfmt.main fix src test
fi
