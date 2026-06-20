# FFMForge infrastructure (OpenTofu)

Serverless AWS infrastructure for FFMForge, managed with **OpenTofu**. We do
not use Terraform CLI for this project. OpenTofu keeps Terraform-compatible HCL
syntax and local filenames (for example the `terraform {}` settings block and
`.terraform/` plugin directory), but the supported command is `tofu`.

Values such as region, profile, hosted zone id/name, domain name, bucket names,
and image URI are deployment inputs and must not be committed.

The Lambda itself receives a single `FFMFORGE_CONFIG` HOCON environment value.
OpenTofu constructs it from the data bucket and TTL variables; do not add
separate Lambda environment variables for each setting unless there is a clear
runtime reason.

## What it creates

- Private frontend S3 bucket, served through CloudFront with Origin Access
  Control.
- ACM certificate and Route53 validation/alias records for the configured
  frontend domain.
- Private FIT data S3 bucket with encryption, browser CORS for presigned
  uploads/downloads, and a 1-day lifecycle backstop on `fit/`.
- Existing ECR repository lookup for the Lambda container image.
- Lambda execution role and least-privilege S3 policy.
- Lambda function using the supplied image URI.
- API Gateway HTTP API routed through CloudFront at `/ffmforge/v1/*`.
- EventBridge schedule that invokes the Lambda cleanup path every 15 minutes.

## Configuration

Use environment variables or an ignored OpenTofu variable file. Do not commit
real values.

```bash
export TF_VAR_aws_region="..."
export TF_VAR_aws_profile="..."
export TF_VAR_hosted_zone_id="..."
export TF_VAR_hosted_zone_name="..."
export TF_VAR_domain_name="..."
export TF_VAR_frontend_bucket_name="..."
export TF_VAR_data_bucket_name="..."
export TF_VAR_ecr_repository_name="..."
export TF_VAR_lambda_image_uri="..."
```

`infra/local.auto.tfvars` is also supported for local use, and is ignored by git.

## Bootstrap

The Lambda image must exist before the full stack can create the function. ECR
is expected to already exist; OpenTofu looks it up by `ecr_repository_name` and
does not create or manage that repository.

1. Build the local Lambda image:

   ```bash
   ./gradlew docker
   ```

2. Tag and push it to the existing ECR repo:

   ```bash
   aws ecr get-login-password --region "$TF_VAR_aws_region" \
     | docker login --username AWS --password-stdin "<account>.dkr.ecr.<region>.amazonaws.com"

   docker tag ffm-forge:1.0 "<account>.dkr.ecr.<region>.amazonaws.com/<repo>:latest"
   docker push "<account>.dkr.ecr.<region>.amazonaws.com/<repo>:latest"
   ```

3. Set `TF_VAR_lambda_image_uri` to the pushed image URI, preferably an immutable
   tag or digest once we move beyond the first smoke deployment.

4. Apply the full stack:

   ```bash
   tofu apply
   ```

State is local for now and ignored by git. A remote encrypted state backend is a
follow-up.
