# check=skip=FromPlatformFlagConstDisallowed
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY libs /app/lib/
COPY static /app/static/
COPY docker-run.sh /app/
RUN chmod 755 /app/docker-run.sh
ENV PORT=8443
ENV STATIC_DIR=/app/static
CMD ["/app/docker-run.sh"]
