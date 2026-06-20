variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  sensitive   = true
}

variable "aws_profile" {
  description = "Local AWS profile used by OpenTofu"
  type        = string
  sensitive   = true
}

variable "hosted_zone_id" {
  description = "Route53 hosted zone id"
  type        = string
  sensitive   = true
}

variable "hosted_zone_name" {
  description = "Route53 hosted zone name"
  type        = string
  sensitive   = true
}

variable "domain_name" {
  description = "Public frontend domain name"
  type        = string
  sensitive   = true
}

variable "frontend_bucket_name" {
  description = "S3 bucket for private frontend assets"
  type        = string
  sensitive   = true
}

variable "data_bucket_name" {
  description = "S3 bucket for uploaded and generated FIT files"
  type        = string
  sensitive   = true
}

variable "lambda_image_uri" {
  description = "ECR image URI for the Lambda container image"
  type        = string
  sensitive   = true
}

variable "ecr_repository_name" {
  description = "Existing ECR repository name for the Lambda container image"
  type        = string
  sensitive   = true
}

variable "app_name" {
  description = "Name used for AWS resource names"
  type        = string
  default     = "ffm-forge"
}

variable "session_ttl_minutes" {
  description = "Application-enforced working-object TTL"
  type        = number
  default     = 120
}

variable "presign_ttl_minutes" {
  description = "Presigned upload/download URL lifetime"
  type        = number
  default     = 15
}
