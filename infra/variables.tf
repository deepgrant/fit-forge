variable "region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "aws_profile" {
  description = "Shared-credentials profile to use"
  type        = string
  default     = "default"
}

variable "bucket_name" {
  description = "S3 bucket holding uploaded/merged .fit objects (short-lived, app-enforced TTL)"
  type        = string
  default     = "fit-forge-uploads"
}

variable "app_name" {
  description = "Name used for the IAM role/policy and ECR repository"
  type        = string
  default     = "fit-forge"
}
