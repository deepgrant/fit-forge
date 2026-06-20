FROM public.ecr.aws/lambda/java:21

COPY libs/*.jar ${LAMBDA_TASK_ROOT}/lib/

CMD ["ffmforge.lambda.FFMForgeLambda::handleRequest"]
