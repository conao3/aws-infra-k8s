# 013: recapcoach backend

The `recapcoach` module builds the backend of [ts-recapcoach](https://github.com/conao3/ts-recapcoach). It does not run on k8s and is not part of `deploy all`.

## Resources

| Group | Resources |
|-------|-----------|
| Entry | API Gateway REST API (stage `prod`, access logs, throttling) with a Cognito authorizer; API Gateway account settings (role for access logs). Optional (`EnableWaf=true`): WAFv2 web ACL with a per-IP rate limit and `AWSManagedRulesCommonRuleSet` (`SizeRestrictions_BODY` set to Count) |
| Compute | API Lambda (Hono monolith, 29 s) and worker Lambda (120 s, SQS event source, max concurrency 5) |
| Async jobs | SNS topic, SQS queue (visibility timeout 720 s = 6 x the worker timeout) and DLQ, DynamoDB `jobs` table (TTL `expiresAt`, key `userId` + `jobId`) |
| Users | DynamoDB `users` table (key: lowercase `email`; attributes `userId`, `plan`). Only the administrator writes it |
| Uploads | S3 bucket for transcripts: public access blocked, SSE-S3, TLS only, `uploads/` expires in 1 day, CORS allows POST from the extension origins only |
| IAM | One role per Lambda, scoped to its own log group, tables, topic/queue and `uploads/*` |

Transcripts do not pass through API Gateway (or WAF, when enabled). The extension gets a presigned POST from `POST /v1/uploads` (key pinned to `uploads/<userId>/<uploadId>.json`, 1 byte to 2 MB, `application/json`, 5 minutes) and posts the file to S3 directly.

Cognito is shared and lives in the `cognito` stack. The `cognito` stack adds an app client for the extension (authorization code + PKCE, no secret, callback `https://<extension id>.chromiumapp.org/`, providers Google and COGNITO) and exports `UserPoolArn` and `RecapcoachUserPoolClient`. The `recapcoach` stack imports them (authorizer, and the client ID that the API compares with the token `aud`). The extension IDs live in `src/conao3/aws_infra_k8s/recapcoach_extension.clj`.

## AWS WAF is optional (off by default)

The web ACL and its association are created only when the parameter `EnableWaf` is `true` (deploy with `RECAPCOACH_ENABLE_WAF=true`; unset means `false`). AWS WAF costs about 7 USD per month in fixed fees (web ACL 5 USD + 2 rules 1 USD each), the largest fixed cost of this stack, so the default is no WAF: the plan is to put Cloudflare (Free, `sancode.dev` zone) in front instead. With the WAF off, what stays is the stage throttling (20 requests/s, burst 40), the Cognito authorizer (only `/healthz` and `OPTIONS` pass without a token), and the `aud` / `email_verified` / users table checks in the API.

When enabled the settings are: per-IP rate limit (600 requests per 5 minutes, block) and `AWSManagedRulesCommonRuleSet` with `SizeRestrictions_BODY` (8 KB) overridden to Count, because `/v1/turns` and `/v1/debrief` bodies can be up to 1 MB (the body size is bounded by Hono's `bodyLimit`).

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

## Put it behind Cloudflare (prepared, not enabled)

The template has optional parameters for this. All default to empty / `false` and create nothing.

| Parameter | Deploy env var | Effect |
|-----------|----------------|--------|
| `ApiDomainName` | `RECAPCOACH_API_DOMAIN_NAME` | Regional custom domain (`AWS::ApiGateway::DomainName`) plus a base path mapping to the `prod` stage. Created only when both this and the certificate are set |
| `ApiCertificateArn` | `RECAPCOACH_API_CERTIFICATE_ARN` | ACM certificate for the domain, in the same region as the API (ap-northeast-1) |
| `DisableExecuteApiEndpoint` | `RECAPCOACH_DISABLE_EXECUTE_API_ENDPOINT=true` | Disables the default `execute-api` endpoint, so the API can only be reached through the custom domain |

The output `RecapcoachApiDomainTarget` is the regional domain name to point the Cloudflare CNAME at. With the custom domain the URL has no stage in the path (`https://<domain>/v1/...`).

Steps:

1. Pick a domain one label under the zone, such as `dev-recapcoach-api.sancode.dev`. Cloudflare Universal SSL covers `*.sancode.dev` only (one level), so `a.b.sancode.dev` would not be covered.
2. Request an ACM certificate for it in ap-northeast-1 and add the DNS validation CNAME in Cloudflare (DNS only, see `notes/aws.md`).
3. Deploy with `RECAPCOACH_API_DOMAIN_NAME` and `RECAPCOACH_API_CERTIFICATE_ARN`.
4. In Cloudflare add a proxied CNAME from the domain to `RecapcoachApiDomainTarget`, with SSL/TLS mode Full (strict). Put rate limiting and other rules on the Cloudflare side.
5. Check the API through the domain, then redeploy with `RECAPCOACH_DISABLE_EXECUTE_API_ENDPOINT=true`.
6. Restrict the source IPs with a resource policy (not parameterized yet; add it as a fixed setting when Cloudflare goes in front). It allows `execute-api:Invoke` only from the public Cloudflare ranges, both IPv4 and IPv6 (https://www.cloudflare.com/ips/):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Principal": "*", "Action": "execute-api:Invoke", "Resource": "execute-api:/*" },
    {
      "Effect": "Deny",
      "Principal": "*",
      "Action": "execute-api:Invoke",
      "Resource": "execute-api:/*",
      "Condition": { "NotIpAddress": { "aws:SourceIp": ["<Cloudflare IPv4 ranges>", "<Cloudflare IPv6 ranges>"] } }
    }
  ]
}
```

In SAM this is `Auth.ResourcePolicy.IpRangeWhitelist` on the `Api` resource with the ranges from `https://www.cloudflare.com/ips-v4` and `https://www.cloudflare.com/ips-v6`; `bin/update-cloudflare-ips` already fetches these ranges for the CloudFront WAF. Cloudflare changes the ranges rarely, so refresh them when they change.
