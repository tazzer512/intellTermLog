#!/usr/bin/env bash
# Simulates two apps logging at once. Right-click > "Run 'demo.sh' in Log Console" to try the plugin.

# Needs bash ($RANDOM); `sh demo.sh` runs dash on Debian/Ubuntu, where every line would be an error.
[ -z "$BASH_VERSION" ] && exec bash "$0" "$@"

app() {
  local name=$1
  for i in $(seq 1 200); do
    ts=$(date +%H:%M:%S.%3N)
    case $((RANDOM % 12)) in
      0) echo "$ts ERROR [$name] c.e.OrderService - Failed to process order $i"
         echo "java.lang.IllegalStateException: order $i is locked"
         echo "	at com.example.OrderService.process(OrderService.java:42)"
         echo "	at com.example.OrderController.post(OrderController.java:17)"
         echo "Caused by: java.sql.SQLException: lock timeout"
         echo "	... 12 more" ;;
      1|2) echo "$ts WARN  [$name] c.e.Client - Slow response from stub: ${RANDOM}ms" ;;
      3) echo "$ts DEBUG [$name] c.e.Cache - cache miss for key $i" ;;
      *) echo "$ts INFO  [$name] c.e.App - Handled request $i (no ERROR here, level is INFO)" ;;
    esac
    sleep 0.$((RANDOM % 5))
  done
}

app stub-server &
app order-app &
wait
