FROM alpine
RUN --mount=type=bind,source=mounted.txt,target=/mounted.txt \
    cp /mounted.txt /from-mount.txt
CMD ["sh", "-c", "cat /from-mount.txt && sleep infinity"]
