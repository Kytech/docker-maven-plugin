# Everything in here needs BuildKit (the classic builder fails on the --mount and the here-document).
FROM alpine AS builder

# Bind mount of a file of the build context, which doesn't end up in a layer
RUN --mount=type=bind,source=mounted.txt,target=/mounted.txt \
    cp /mounted.txt /from-mount.txt

RUN --mount=type=cache,target=/var/cache/dmp-it \
    echo "from cache mount" > /var/cache/dmp-it/marker && cp /var/cache/dmp-it/marker /from-cache.txt

RUN <<EOF
echo "from here-document" > /from-heredoc.txt
EOF

FROM alpine
COPY --from=builder /from-mount.txt /from-cache.txt /from-heredoc.txt /results/
# An image which is not an earlier stage: the plugin pulls it before the build (with its credentials, if needed),
# as BuildKit can't authenticate to a registry without a session
COPY --from=busybox:1.36.1 /etc/passwd /results/passwd-from-image

CMD ["sh", "-c", "cat /results/from-*.txt && grep -q '^root:' /results/passwd-from-image && echo from-image-ok && sleep infinity"]
