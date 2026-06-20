# fit-forge infrastructure (OpenTofu)

Beginnings of the AWS infrastructure for fit-forge. This provisions the data and
image foundation; cloud **compute (ECS/EKS) is deferred**.

## What it creates
- **S3 bucket** (`s3.tf`) — private, encrypted, with a **1-day lifecycle rule** on
  the `fit/` prefix as the hard backstop for the app's short (~2h) TTL.
- **IAM** (`iam.tf`) — a least-privilege policy (`Get/Put/Delete/ListBucket`) and a
  role the backend container assumes. The trust policy is a placeholder
  (`ecs-tasks`) until the compute layer lands; on EKS it becomes an IRSA trust.
  The app reads credentials via the AWS Default Credentials Provider Chain, so no
  keys are ever configured in code.
- **ECR repository** (`ecr.tf`) — for the backend image (scan-on-push,
  untagged-image expiry).

## Usage
```bash
cd infra
tofu init
tofu plan   -var="bucket_name=my-fit-forge-bucket"
tofu apply  -var="bucket_name=my-fit-forge-bucket"
```
State is local for now (a remote backend — S3 + DynamoDB lock — is a follow-up).
Requires OpenTofu ~> 1.8 and AWS credentials in your environment
(`AWS_PROFILE` / SSO / env).

## Local development
You do **not** need this for local dev or tests. Local dev uses **LocalStack**
via `docker-compose.yml` at the repo root (the backend auto-creates its bucket
when `LOCAL_ENSURE_BUCKET=true`); unit tests connect to a LocalStack started out
of band (see `docs/architecture.md`).
