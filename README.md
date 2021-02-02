# Crash envoy plane
This is a test envoy management server to expose crash behaviour of udp clusters as in [https://github.com/envoyproxy/envoy/issues/14866].

## Building
```
docker build -t c-m-s:latest .
```

## Running
 * start control plane
```
docker run --rm -p8080:8080 -p12345:12345 c-m-s:latest
```
 * connect envoy using sample config file
```
 envoy -c envoy/envoy-dynamic-v3.yaml --concurrency 1
```
 * trigger change - open in browser [http://127.0.0.1:88080] or use curl:
```
curl -s -XPOST 127.0.0.1:8080/triggerChange
```
