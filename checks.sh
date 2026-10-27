#!/bin/bash
# Commit checks, run by pre-build.sh and before pushing.
set -euo pipefail

fail() {
  echo >&2
  echo "checks failed: $1" >&2
  echo "Fix with:" >&2
  echo "  ./changes.py" >&2
  echo "  sed -i 's#edd-core-scaffold {:mvn/version \"[0-9.]*\"}#edd-core-scaffold {:mvn/version \"'\$(./version.sh)'\"}#' ${scaffold_docs[*]}" >&2
  echo "  git add CHANGES.md ${scaffold_docs[*]} && git commit --amend --no-edit" >&2
  echo "  ./checks.sh" >&2
  exit 1
}

scaffold_docs=(README.md modules/edd-core-scaffold/README.md AGENTS.md)

./changes.py --shallow --check || fail "CHANGES.md does not start with the last commit message."

version=$(./version.sh)
expected="edd-core-scaffold {:mvn/version \"${version}\"}"
for doc in "${scaffold_docs[@]}"; do
  found=$(grep -oE 'edd-core-scaffold \{:mvn/version "[^"]*"\}' "${doc}" | sort -u || true)
  if [ "${found}" != "${expected}" ]; then
    fail "${doc} shows ${found:-no scaffold version}, this commit releases ${version}."
  fi
done
echo "Scaffold docs show version ${version}"
