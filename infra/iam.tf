# Least-privilege access to the uploads bucket — this is what the app's AWS Default
# Credentials Provider Chain binds to once the role is assumed by the container.
data "aws_iam_policy_document" "s3_access" {
  statement {
    sid       = "ObjectRW"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.uploads.arn}/*"]
  }

  statement {
    sid       = "ListBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.uploads.arn]
  }
}

resource "aws_iam_policy" "s3_access" {
  name   = "${var.app_name}-s3-access"
  policy = data.aws_iam_policy_document.s3_access.json
}

# Role the backend container assumes. The trust policy is a placeholder (ECS task
# service principal) until the compute layer (ECS/EKS) lands — on EKS this becomes
# an IRSA trust on the cluster OIDC provider.
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.app_name}-app"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

resource "aws_iam_role_policy_attachment" "app_s3" {
  role       = aws_iam_role.app.name
  policy_arn = aws_iam_policy.s3_access.arn
}
