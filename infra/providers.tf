provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile
}

data "aws_route53_zone" "app" {
  zone_id = var.hosted_zone_id
}
