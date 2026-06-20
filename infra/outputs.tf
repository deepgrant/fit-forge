output "bucket_name" {
  description = "S3 bucket for uploaded/merged .fit objects"
  value       = aws_s3_bucket.uploads.bucket
}

output "app_role_arn" {
  description = "IAM role the backend container assumes (least-privilege S3 access)"
  value       = aws_iam_role.app.arn
}

output "ecr_repository_url" {
  description = "ECR repository URL for the backend image"
  value       = aws_ecr_repository.app.repository_url
}
