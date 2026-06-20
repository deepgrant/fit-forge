output "frontend_bucket_name" {
  description = "S3 bucket for frontend assets"
  value       = aws_s3_bucket.frontend.bucket
  sensitive   = true
}

output "data_bucket_name" {
  description = "S3 bucket for short-lived FIT files"
  value       = aws_s3_bucket.data.bucket
  sensitive   = true
}

output "cloudfront_distribution_id" {
  description = "CloudFront distribution id"
  value       = aws_cloudfront_distribution.app.id
}

output "cloudfront_domain_name" {
  description = "CloudFront domain name"
  value       = aws_cloudfront_distribution.app.domain_name
}

output "api_endpoint" {
  description = "API Gateway endpoint backing CloudFront"
  value       = aws_apigatewayv2_api.http.api_endpoint
}

output "lambda_function_name" {
  description = "Lambda function name"
  value       = aws_lambda_function.api.function_name
}

output "ecr_repository_url" {
  description = "ECR repository URL for the Lambda image"
  value       = data.aws_ecr_repository.app.repository_url
}
