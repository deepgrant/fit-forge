data "aws_ecr_repository" "app" {
  name = var.ecr_repository_name
}
