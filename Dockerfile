# check=skip=FromPlatformFlagConstDisallowed
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY libs /app/lib/
COPY docker-run.sh /app/
RUN chmod 755 /app/docker-run.sh
ENV PORT=8080
ENV STATIC_DIR=/app/static
# S3 config is supplied at runtime (compose / k8s): S3_BUCKET, AWS_REGION,
# optional S3_ENDPOINT (LocalStack). Credentials come from the AWS default chain.
EXPOSE 8080
CMD ["/app/docker-run.sh"]
