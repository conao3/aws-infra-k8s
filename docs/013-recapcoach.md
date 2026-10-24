# 013: recapcoach backend

The `recapcoach` module builds the backend of [ts-recapcoach](https://github.com/conao3/ts-recapcoach). It does not run on k8s and is not part of `deploy all`.

## Resources

| Group | Resources |
|-------|-----------|
| Entry | API Gateway REST API (stage `prod`, access logs, throttling) with a Cognito authorizer; WAFv2 web ACL (per-IP rate limit, `AWSManagedRulesCommonRuleSet` with `SizeRestrictions_BODY` set to Count); API Gateway account settings (role for access logs) |
| Compute | API Lambda (Hono monolith, 29 s) and worker Lambda (120 s, SQS event source, max concurrency 5) |
| Async jobs | SNS topic, SQS queue (visibility timeout 720 s = 6 x the worker timeout) and DLQ, DynamoDB `jobs` table (TTL `expiresAt`, key `userId` + `jobId`) |
| Users | DynamoDB `users` table (key: lowercase `email`; attributes `userId`, `plan`). Only the administrator writes it |
| Uploads | S3 bucket for transcripts: public access blocked, SSE-S3, TLS only, `uploads/` expires in 1 day, CORS allows POST from the extension origins only |
| IAM | One role per Lambda, scoped to its own log group, tables, topic/queue and `uploads/*` |

Transcripts do not pass through API Gateway or WAF. The extension gets a presigned POST from `POST /v1/uploads` (key pinned to `uploads/<userId>/<uploadId>.json`, 1 byte to 2 MB, `application/json`, 5 minutes) and posts the file to S3 directly.

Cognito is shared and lives in the `cognito` stack. The `cognito` stack adds an app client for the extension (authorization code + PKCE, no secret, callback `https://<extension id>.chromiumapp.org/`, providers Google and COGNITO) and exports `UserPoolArn` and `RecapcoachUserPoolClient`. The `recapcoach` stack imports them (authorizer, and the client ID that the API compares with the token `aud`). The extension IDs live in `src/conao3/aws_infra_k8s/recapcoach_extension.clj`.

## Prerequisites

1. Secrets Manager `<prefix>-secret` has `google-client-id` and `google-client-secret` ([002](002-prerequisites.md)).
2. SSM parameters (String): `/<prefix>/recapcoach/ai-gateway-api-key` and `/<prefix>/recapcoach/ai-gateway-model`.
3. Build the Lambda bundles in ts-recapcoach: `pnpm --filter @recapcoach/api build` (`apps/api/dist/lambda/index.mjs` and `worker.mjs`).

## Deploy

```bash
export AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy cognito
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy recapcoach
```

Both commands run `sam validate --lint` first. Delete in the reverse order (recapcoach, then cognito): the cognito exports cannot be deleted while recapcoach imports them.

## Register a user

Users are registered by the administrator with `pnpm admin:add-user` in ts-recapcoach, which calls `aws dynamodb put-item` on the `users` table (`RecapcoachUsersTable` output).
