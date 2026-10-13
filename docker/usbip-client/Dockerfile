# Tiny privileged sidecar that imports the devices exported by macos/usbip-share
# into the Docker Desktop VM's built-in USB/IP virtual host controller.
FROM alpine:3.24
RUN apk add --no-cache usbip-utils
COPY --chmod=755 attach-loop.sh /usr/local/bin/attach-loop
ENTRYPOINT ["/usr/local/bin/attach-loop"]
