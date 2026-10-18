# Starts the containerised demo over from nothing: the services stop, their database is dropped, and what the old run left in Kafka and Redis is
# removed. Then:  docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.services.yml up -d   (a fresh database, seeded again).
#
# Why not just drop the database: the broker outlives it. Events of the old database stay on the topics, and a consumer that has not read them yet
# (a new consumer group, or the first start after a reset) replays them against a database that has never heard of those students and enrollments;
# they are retried and set aside as dead letters. The old announcements in Redis are removed for the same reason: the gateway would route to
# instances that are gone. Nothing outside this stack's own names is touched (the databases other projects keep in the same containers stay).
#
#   pwsh infra/scripts/reset-demo.ps1          (asks first; -Yes skips the question)
param([switch]$Yes, [string]$Database = $(if ($env:SETU_DB_NAME) { $env:SETU_DB_NAME } else { 'setu_demo' }))

$ErrorActionPreference = 'Stop'
$services = 'gateway', 'ai', 'notification', 'attendance', 'assessment', 'learning', 'academic', 'identity', 'core'

if (-not $Yes) {
    Write-Host "This stops the Setu services, drops the database '$Database', and deletes every setu.* topic, setu-* consumer group and setu:services:* Redis key."
    if ((Read-Host 'Type yes to continue') -ne 'yes') { Write-Host 'Nothing changed.'; exit 1 }
}

Write-Host '1/4 stopping the services'
foreach ($s in $services) { docker stop "setu-$s-1" 2>$null | Out-Null }
# the one-shot that creates the database must run again, so its finished container goes too
docker container rm -f setu-db-init-1 2>$null | Out-Null

Write-Host "2/4 dropping the database '$Database'"
docker exec setu-postgres-1 psql -U setu -d postgres -c "DROP DATABASE IF EXISTS $Database WITH (FORCE)"

Write-Host '3/4 deleting this stack''s topics and consumer groups from Kafka'
$kafka = '/opt/kafka/bin'
$topics = docker exec setu-kafka-1 "$kafka/kafka-topics.sh" --bootstrap-server kafka:29092 --list | Where-Object { $_ -like 'setu.*' }
foreach ($t in $topics) { docker exec setu-kafka-1 "$kafka/kafka-topics.sh" --bootstrap-server kafka:29092 --delete --topic $t | Out-Null }
$groups = docker exec setu-kafka-1 "$kafka/kafka-consumer-groups.sh" --bootstrap-server kafka:29092 --list | Where-Object { $_ -like 'setu-*' }
foreach ($g in $groups) { docker exec setu-kafka-1 "$kafka/kafka-consumer-groups.sh" --bootstrap-server kafka:29092 --delete --group $g | Out-Null }
Write-Host ("    {0} topics, {1} consumer groups removed" -f @($topics).Count, @($groups).Count)

Write-Host '4/4 clearing the service announcements from Redis'
$keys = docker exec setu-redis-1 redis-cli --scan --pattern 'setu:services:*'
foreach ($k in $keys) { docker exec setu-redis-1 redis-cli DEL $k | Out-Null }

Write-Host 'Done. Start it again with:'
Write-Host '  docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.services.yml up -d'
