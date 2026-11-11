# edd-core-aws

AWS for edd-core services: the Lambda custom runtime (`aws.lambda`), AWS clients
(`aws.*`, `sdk.aws.*`), the DynamoDB event store, the S3 view store, CloudWatch
EMF metrics (`edd.aws-emf.core`) and Cognito.

```clojure
(-> ctx
    (edd.dynamodb.event-store/register)
    (edd.s3.view-store/register)
    (aws.cognito/register)                                           ; keys for aws.cognito/authenticate
    (edd.client.deps/register {:credentials (aws.cognito/credentials)})) ; Cognito token for remote deps
```

- `aws.filters/from-bucket` is a runtime filter turning S3 upload notifications
  (direct or through SQS) into `:object-uploaded` commands.
- `aws.lambda/lambda-custom-runtime` is the Lambda custom runtime; the Java
  runtime with SnapStart is `edd-java-lambda-runtime`.
