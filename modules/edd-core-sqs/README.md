# edd-core-sqs

Amazon SQS transport feature and the SQS client (`sdk.aws.sqs`).

```clojure
(edd.sqs.distributor/register ctx)
```

- Publishes in batches of 10 messages / 1 MiB; a single oversized message is
  offloaded to S3 by `sqs-publish`. Pass `{:max-message-size n}` (or
  `SqsMaxMessageSize`) for queues with a smaller `MaximumMessageSize`.
- Consumers long-poll (4 s) and delete a message after it succeeds.
- Provides `:ack-delivered`, so a Lambda SQS batch that partly
  fails deletes only the records that succeeded.
