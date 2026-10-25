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

1. SSM parameters `/<prefix>/cognito/google-client-id` and `/<prefix>/cognito/google-client-secret` exist ([002](002-prerequisites.md), section 3b).
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

## Custom domain behind Cloudflare

dev runs at `https://dev-recapcoach.sancode.dev` (prd `recapcoach.sancode.dev`); the Cognito hosted UI is `dev-auth-k8s.sancode.dev` (prd `auth-k8s.sancode.dev`). Pick domains one label under the zone, because Cloudflare Universal SSL covers `*.sancode.dev` only (one level).

| Parameter | Source | Effect |
|-----------|--------|--------|
| `ApiDomainName` | `<env>-recapcoach.sancode.dev`, or `RECAPCOACH_API_DOMAIN_NAME` | Regional custom domain (`AWS::ApiGateway::DomainName`) plus a base path mapping to the `prod` stage |
| `ApiCertificateArn` | export `<prefix>-RecapcoachCertificateArn` of the `recapcoach-certificate` stack (ap-northeast-1), or `RECAPCOACH_API_CERTIFICATE_ARN` | ACM certificate in the API's region. The domain is created only when the certificate exists |
| `DisableExecuteApiEndpoint` | `true` automatically when the certificate exists (`RECAPCOACH_DISABLE_EXECUTE_API_ENDPOINT=false` keeps it) | The default `execute-api` endpoint is closed, so the API is only reachable through the custom domain |

With the custom domain the URL has no stage in the path (`https://<domain>/v1/...`). The output `RecapcoachApiDomainTarget` is the regional domain name that the Cloudflare CNAME points at.

Steps for a new environment:

1. `deploy certificate` (us-east-1, Cognito) and `deploy recapcoach-certificate` (ap-northeast-1, API). Each blocks until the DNS validation record exists. Read it with `aws acm describe-certificate --region <r> --certificate-arn <arn> --query 'Certificate.DomainValidationOptions[].ResourceRecord'` and add it to the `sancode.dev` zone in Cloudflare as a CNAME, DNS only. The record name is fixed per account and domain, so a record from a previous certificate for the same domain is reused.
2. `deploy cognito`. A user pool has one domain: delete the old prefix domain first (`aws cognito-idp delete-user-pool-domain`) when switching an existing pool. Point `<env>-auth-k8s.sancode.dev` at the new CloudFront distribution (`aws cognito-idp describe-user-pool-domain --domain <domain>`, `CloudFrontDistribution`) with a **DNS only** CNAME.
3. `deploy recapcoach`. Add a CNAME `<env>-recapcoach` to `RecapcoachApiDomainTarget`. It is **proxied** (Cloudflare in front) and needs SSL/TLS mode Full (strict) or Full. No Cloudflare Access application covers these two host names: Access applications are per host name (`Dev App1 Access` and so on), so the extension and the browser flow are not challenged.
4. When `DisableExecuteApiEndpoint` changes, API Gateway needs a new deployment of the stage before it takes effect: `aws apigateway create-deployment --rest-api-id <id> --stage-name prod`.

The Google OAuth client ("Sancode Auth") must list `https://<cognito domain>/oauth2/idpresponse` as an authorized redirect URI. `dev-auth-k8s.sancode.dev` is registered.

Not done yet: restricting the API to Cloudflare source IPs. The proxied record hides the origin, but the regional endpoint still answers to anyone who sends the right `Host` and SNI. Add a resource policy (`Auth.ResourcePolicy.IpRangeWhitelist` in SAM, ranges from `https://www.cloudflare.com/ips-v4` and `ips-v6`; `bin/update-cloudflare-ips` already fetches them for the CloudFront WAF) when you want that.
