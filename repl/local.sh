#!/bin/bash

set -e

docker-compose down
if [[ "$(grep "vm.max_map_count" /etc/sysctl.conf)" == "" ]]; then
  sudo sysctl -w vm.max_map_count=262144
fi
docker-compose up -d

# First wait for ES to start...
host="https://admin:admin@127.0.0.1:9200"
response="null"
count=1
until [[ "$response" = "200" && $count -le 15 ]]; do
    response=$(curl -k -s --write-out %{http_code} --output /dev/null "$host" || echo "Fail")
    >&2 echo "Elastic Search is unavailable ($count) - sleeping:  ${response}"
    sleep 5
    count=$((count+1))
done

echo Continue

sleep 5
flyway -password="no-secret" \
       -schemas=test \
       -url=jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres \
       -locations="filesystem:${PWD}/../sql/files/edd" migrate
