resource "aws_lambda_function" "api" {
  function_name = var.app_name
  package_type  = "Image"
  image_uri     = var.lambda_image_uri
  role          = aws_iam_role.lambda.arn
  timeout       = 60
  memory_size   = 2048

  environment {
    variables = {
      FFMFORGE_CONFIG = <<-EOC
        ffmforge {
          port = 8080
          static-dir = "/app/static"
          session-ttl = ${var.session_ttl_minutes} minutes
          presign-ttl = ${var.presign_ttl_minutes} minutes
          s3 {
            bucket = "${aws_s3_bucket.data.bucket}"
          }
        }
      EOC
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.lambda_basic,
    aws_iam_role_policy_attachment.lambda_s3,
  ]
}

resource "aws_cloudwatch_event_rule" "cleanup" {
  name                = "${var.app_name}-cleanup"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "cleanup" {
  rule = aws_cloudwatch_event_rule.cleanup.name
  arn  = aws_lambda_function.api.arn

  input = jsonencode({
    source = "ffmforge.cleanup"
  })
}

resource "aws_lambda_permission" "allow_cleanup" {
  statement_id  = "AllowExecutionFromEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.cleanup.arn
}
