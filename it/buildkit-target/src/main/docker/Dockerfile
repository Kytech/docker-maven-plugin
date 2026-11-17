FROM alpine AS base
RUN --mount=type=bind,source=Dockerfile,target=/Dockerfile \
    echo "base" > /stage.txt
CMD ["sh", "-c", "cat /stage.txt && sleep infinity"]

FROM base AS final
RUN echo "final" > /stage.txt
