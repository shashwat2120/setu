# Shows the transactional outbox -> Kafka path for a demo, WITHOUT a Java client on the host.
# Everything runs inside the Docker containers (Linux JVM), so it is unaffected by this Windows
# machine's JDK NIO "Unable to establish loopback connection" defect that can delay the app's own producer.
#
#   .\infra\scripts\kafka-demo.ps1 status             # outbox rows by status + message count per topic
#   .\infra\scripts\kafka-demo.ps1 tail grades        # last events on setu.grades, with headers
#   .\infra\scripts\kafka-demo.ps1 follow             # live-stream every setu.* event as it arrives (Ctrl+C to stop)
#
# Prereq: `docker compose up -d` in infra/docker (the broker exposes an in-container listener on 29092).
param(
    [Parameter(Position = 0)][ValidateSet('status', 'tail', 'follow')][string]$Command = 'status',
    [Parameter(Position = 1)][string]$Topic = 'grades',
    [int]$Count = 5,
    [string]$Database = 'setu_demo'
)

$kafka = 'setu-kafka-1'
$pg = 'setu-postgres-1'
$bin = '/opt/kafka/bin'
$bootstrap = 'localhost:29092'
$env:MSYS_NO_PATHCONV = '1'

function Invoke-Kafka([string[]]$ToolArgs) {
    docker exec $kafka @ToolArgs
}

switch ($Command) {
    'status' {
        Write-Host "`n== Outbox (rows written in the same DB transaction as the business change) ==" -ForegroundColor Cyan
        docker exec $pg psql -U setu -d $Database -c "select status, count(*) from governance.outbox_events group by status order by status"
        Write-Host "== Kafka: messages per topic ==" -ForegroundColor Cyan
        Invoke-Kafka @("$bin/kafka-get-offsets.sh", '--bootstrap-server', $bootstrap, '--time', '-1') |
            Where-Object { $_ -like 'setu.*' } |
            ForEach-Object { $p = $_ -split ':'; '{0,-28} {1,5}' -f $p[0], $p[2] }
        Write-Host "`nIf NEW > 0: the app's producer is still starting (retries every 2s, nothing is lost) - see docs/demo-script.md step 0.`n"
    }
    'tail' {
        $t = if ($Topic -like 'setu.*') { $Topic } else { "setu.$Topic" }
        Write-Host "`n== Last $Count events on $t (headers = event_id,event_type,event_version,correlation_id,tenant_id) ==" -ForegroundColor Cyan
        Invoke-Kafka @("$bin/kafka-console-consumer.sh", '--bootstrap-server', $bootstrap, '--topic', $t,
            '--partition', '0', '--offset', 'earliest', '--max-messages', "$Count", '--timeout-ms', '15000',
            '--property', 'print.headers=true', '--property', 'print.key=true')
    }
    'follow' {
        Write-Host "`n== Live: every setu.* event as it is published (Ctrl+C to stop) ==" -ForegroundColor Cyan
        Invoke-Kafka @("$bin/kafka-console-consumer.sh", '--bootstrap-server', $bootstrap, '--include', 'setu\..*',
            '--property', 'print.headers=true', '--property', 'print.topic=true')
    }
}
