# 013: sanrecap backend

The `sanrecap` module builds the backend of [sanrecap](https://github.com/sancode-org/sanrecap). It does not run on k8s and is not part of `deploy all`.

## Resources

| Group | Resources |
|-------|-----------|
| Entry | API Gateway REST API (stage `prod`, access logs, throttling) with a Cognito authorizer; API Gateway account settings (role for access logs). Optional (`EnableWaf=true`): WAFv2 web ACL with a per-IP rate limit and `AWSManagedRulesCommonRuleSet` (`SizeRestrictions_BODY` set to Count) |
| Compute | API Lambda (Hono monolith, 29 s) and worker Lambda (120 s, SQS event source, max concurrency 5) |
| Async jobs | SNS topic, SQS queue (visibility timeout 720 s = 6 x the worker timeout) and DLQ, DynamoDB `jobs` table (TTL `expiresAt`, key `userId` + `jobId`) |
| Users | DynamoDB `users` table (key: lowercase `email`; attributes `userId`, `plan`). The API creates a row on the first login (conditional `PutItem`); the billing Lambda updates `plan` |
| Usage | DynamoDB `usage` table (key `userId` + `sk`): `videos#<period>` counts videos (`lifetime` for free, `YYYY-MM` for paid), `session#<job id>` counts the turns, debriefs and speech clips of one interview and expires by TTL (`expiresAt`). The API Lambda has `GetItem` / `PutItem` / `UpdateItem` on it. No content is stored |
| Clips | DynamoDB `clips` table (key `clipId` = `<videoId>-<startSec>`; attributes `videoId`, `startSec`, `endSec`, `title`, `channel`, `genre`, `level`, `hook`, `createdAt`). The API Lambda has `Scan` / `GetItem` on it (`GET /v1/clips`, env `CLIPS_TABLE`). Written only by the operator's batch. No captions or key points are stored |
| Uploads | S3 bucket for transcripts: public access blocked, SSE-S3, TLS only, `uploads/` expires in 1 day, CORS allows POST from the extension origins only |
| IAM | One role per Lambda, scoped to its own log group, tables, topic/queue and `uploads/*` |

Transcripts do not pass through API Gateway (or WAF, when enabled). The extension gets a presigned POST from `POST /v1/uploads` (key pinned to `uploads/<userId>/<uploadId>.json`, 1 byte to 2 MB, `application/json`, 5 minutes) and posts the file to S3 directly.

Cognito is shared and lives in the `cognito` stack. The `cognito` stack adds an app client for the extension (authorization code + PKCE, no secret, callback `https://<extension id>.chromiumapp.org/`, providers Google and COGNITO) and exports `UserPoolArn` and `SanrecapUserPoolClient`. The `sanrecap` stack imports them (authorizer, and the client ID that the API compares with the token `aud`). The extension IDs live in `src/conao3/aws_infra_k8s/sanrecap_extension.clj`. The same client also serves the account page that the API itself serves at `GET /account` (`https://sanrecap.sancode.dev/account` in prd, `https://dev-sanrecap.sancode.dev/account` in dev): that URL is a callback and logout URL of the client. `/account`, `/healthz` and `/` (a redirect to the product page https://sancode.dev/services/sanrecap) are the only paths that skip the authorizer; the API gets `ACCOUNT_URL` and `AUTH_DOMAIN` so it can render the page and accept the URL as the Stripe return URL.

## AWS WAF is optional (off by default)

The web ACL and its association are created only when the parameter `EnableWaf` is `true` (deploy with `SANRECAP_ENABLE_WAF=true`; unset means `false`). AWS WAF costs about 7 USD per month in fixed fees (web ACL 5 USD + 2 rules 1 USD each), the largest fixed cost of this stack, so the default is no WAF: the plan is to put Cloudflare (Free, `sancode.dev` zone) in front instead. With the WAF off, what stays is the stage throttling (20 requests/s, burst 40), the Cognito authorizer (only `/healthz`, `/account`, `/` and `OPTIONS` pass without a token), and the `aud` / `email_verified` / users table checks in the API.

When enabled the settings are: per-IP rate limit (600 requests per 5 minutes, block) and `AWSManagedRulesCommonRuleSet` with `SizeRestrictions_BODY` (8 KB) overridden to Count, because `/v1/turns` and `/v1/debrief` bodies can be up to 1 MB (the body size is bounded by Hono's `bodyLimit`).

## Prerequisites

1. SSM parameters `/<prefix>/cognito/google-client-id` and `/<prefix>/cognito/google-client-secret` exist ([002](002-prerequisites.md), section 3b).
2. SSM parameters (String): `/<prefix>/sanrecap/ai-gateway-api-key` and `/<prefix>/sanrecap/ai-gateway-model`. The key is read with a `{{resolve:ssm:...}}` dynamic reference, like the Stripe key, so its value stays out of the stack parameters (`describe-stacks` shows the resolved value of `AWS::SSM::Parameter::Value` parameters). The model ID is a parameter of that type.
   The speech model for `POST /v1/speech` is the plain String parameter `SpeechModel` (default `microsoft/mai-voice-2.1-flash`, passed to the API as `SPEECH_MODEL`) with the optional `SpeechVoice` (`SPEECH_VOICE`, left out when empty). It uses the same AI Gateway key. `deploy sanrecap` passes the default unless `SANRECAP_SPEECH_MODEL` / `SANRECAP_SPEECH_VOICE` are set; an empty `SANRECAP_SPEECH_MODEL` turns server speech off (the API answers 501 and the extension uses the browser voice).
   Each LLM role can use its own AI Gateway model through the plain String parameters `TurnModel` (API, `TURN_MODEL`: interview turns), `SessionModel` (worker, `SESSION_MODEL`: key points and the opening question) and `DebriefModel` (worker, `DEBRIEF_MODEL`: the debrief). They default to empty, which leaves the variable out so the role uses the model of `ai-gateway-model`. `deploy sanrecap` passes `SANRECAP_TURN_MODEL` / `SANRECAP_SESSION_MODEL` / `SANRECAP_DEBRIEF_MODEL` (empty when unset).
3. Build the Lambda bundles in sanrecap: `vp run build-api` (`apps/api/dist/lambda/index.mjs` and `worker.mjs`).

## Deploy

```bash
export AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy cognito
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanrecap
```

Both commands run `sam validate --lint` first. Delete in the reverse order (sanrecap, then cognito): the cognito exports cannot be deleted while sanrecap imports them.

## Register a user

Users are registered by the administrator with `pnpm admin:add-user` in sanrecap, which calls `aws dynamodb put-item` on the `users` table (`SanrecapUsersTable` output).

## Write the clips

The clip list is written by the operator with `vp run admin:clips` in sanrecap, using the operator's own (administrator) AWS credentials; no extra role is needed. The table name is the output `SanrecapClipsTable` (`<prefix>-sanrecap-clips`):

```bash
CLIPS_TABLE=dev-k8s-sanrecap-clips AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1 \
  AI_GATEWAY_API_KEY=... vp run admin:clips   # --dry-run prints the clips, --force redoes videos that have one
```

## Custom domain behind Cloudflare

dev runs at `https://dev-sanrecap.sancode.dev` (prd `sanrecap.sancode.dev`); the Cognito hosted UI is `dev-auth-k8s.sancode.dev` (prd `auth-k8s.sancode.dev`). Pick domains one label under the zone, because Cloudflare Universal SSL covers `*.sancode.dev` only (one level).

| Parameter | Source | Effect |
|-----------|--------|--------|
| `ApiDomainName` | `<env>-sanrecap.sancode.dev`, or `SANRECAP_API_DOMAIN_NAME` | Regional custom domain (`AWS::ApiGateway::DomainName`) plus a base path mapping to the `prod` stage |
| `ApiCertificateArn` | export `<prefix>-SanrecapCertificateArn` of the `sanrecap-certificate` stack (ap-northeast-1), or `SANRECAP_API_CERTIFICATE_ARN` | ACM certificate in the API's region. The domain is created only when the certificate exists |
| `DisableExecuteApiEndpoint` | `true` automatically when the certificate exists (`SANRECAP_DISABLE_EXECUTE_API_ENDPOINT=false` keeps it) | The default `execute-api` endpoint is closed, so the API is only reachable through the custom domain |

With the custom domain the URL has no stage in the path (`https://<domain>/v1/...`). The output `SanrecapApiDomainTarget` is the regional domain name that the Cloudflare CNAME points at.

Steps for a new environment:

1. `deploy certificate` (us-east-1, Cognito) and `deploy sanrecap-certificate` (ap-northeast-1, API). Each blocks until the DNS validation record exists. Read it with `aws acm describe-certificate --region <r> --certificate-arn <arn> --query 'Certificate.DomainValidationOptions[].ResourceRecord'` and add it to the `sancode.dev` zone in Cloudflare as a CNAME, DNS only. The record name is fixed per account and domain, so a record from a previous certificate for the same domain is reused.
2. `deploy cognito`. If the Cloudflare zone still has a CNAME for the auth domain that points at an old CloudFront distribution, Cognito refuses to create the custom domain (`InvalidParameterException ... DNS record that points to another CloudFront distribution`, shown by CloudFormation only as `InvalidRequest`): delete that record first. A user pool has one domain: delete the old prefix domain first (`aws cognito-idp delete-user-pool-domain`) when switching an existing pool. Point `<env>-auth-k8s.sancode.dev` at the new CloudFront distribution (`aws cognito-idp describe-user-pool-domain --domain <domain>`, `CloudFrontDistribution`) with a **DNS only** CNAME.
3. `deploy sanrecap`. Add a CNAME `<env>-sanrecap` to `SanrecapApiDomainTarget`. It is **proxied** (Cloudflare in front) and needs SSL/TLS mode Full (strict) or Full. No Cloudflare Access application covers these two host names: Access applications are per host name (`Dev App1 Access` and so on), so the extension and the browser flow are not challenged.
4. When `DisableExecuteApiEndpoint` changes, API Gateway needs a new deployment of the stage before it takes effect: `aws apigateway create-deployment --rest-api-id <id> --stage-name prod`.

The Google OAuth client ("Sancode Auth") must list `https://<cognito domain>/oauth2/idpresponse` as an authorized redirect URI. `dev-auth-k8s.sancode.dev` is registered.

The API accepts only Cloudflare source IPs. When the custom domain exists, the REST API gets a resource policy (`Auth.ResourcePolicy.IpRangeWhitelist` in SAM: Allow all, then an explicit Deny for sources not in the Cloudflare ranges) built from `resources/cloudflare-ips.edn`. Refresh that file with `bin/update-cloudflare-ips` when Cloudflare changes the ranges (https://www.cloudflare.com/ips/), then redeploy. Without the custom domain no policy is added, so the default endpoint stays testable. Checked on dev: a request sent straight to the regional endpoint with the right `Host` gets `403 ... explicit deny in a resource-based policy`, and the same request through Cloudflare gets 200.

Both Lambdas log in JSON (`LoggingConfig.LogFormat: JSON`), so the application's structured logs can be queried in CloudWatch Logs Insights by `message.event` (event list in sanrecap `README.md`, section "ログ").

## Stripe subscription events (optional)

Billing is built from the Stripe settings in SSM. The six String parameters `/<prefix>/sanrecap/stripe-{secret-key,price-starter,price-pro,price-max,portal-config,event-source}` must all exist; `deploy sanrecap` checks this (`aws ssm get-parameters`) and, only then, adds:

| Resource | Purpose |
|----------|---------|
| `AWS::Events::EventBus` named after `stripe-event-source` (`aws.partner/stripe.com/ed_...`) | Associates the partner event source that Stripe's "event destination" (Amazon EventBridge) created in this AWS account. The source shows as `PENDING` until this bus exists, then `ACTIVE` |
| `AWS::Events::Rule` (`source` prefix `aws.partner/stripe.com`) | Sends every event of the bus to the billing queue |
| SQS `<prefix>-sanrecap-billing` + DLQ `-billing-dlq` | `maxReceiveCount` 5; the queue policy allows only this rule (and TLS) |
| Lambda `<prefix>-sanrecap-billing` (`billing.handler`) | Reads the EventBridge envelope, updates the `users` row (`UpdateItem` only, conditional: never creates a row, never touches `admin` / `local`, never applies an older event over a newer one). An unknown price ID throws so the event ends up in the DLQ |
| API Lambda env `STRIPE_SECRET_KEY`, `STRIPE_PRICE_*`, `STRIPE_PORTAL_CONFIG` | `{{resolve:ssm:...}}` dynamic references, read at deploy time. Without them the billing endpoints answer 501 |

The Stripe side is created by `scripts/stripe-setup.py` in sanrecap (products, monthly prices, portal configuration; idempotent) and by two dashboard steps that have no API: the event destination (Developers → Workbench → Webhooks → Add destination → Amazon EventBridge; events `customer.subscription.created` / `updated` / `deleted`, snapshot payload; name `<prefix>-sanrecap-billing`, your AWS account ID and `ap-northeast-1`) and the restricted API key (Customers read, Products read, Prices read, Checkout Sessions write, Customer Portal write). The destination's source name is also readable from the AWS side with `aws events list-event-sources`.

The Stripe account is shared with sanplan, which has its own destination (`dev-k8s-sanplan-billing`) and its own default portal configuration. A partner event source can be associated with only one bus, so sanrecap has its own destination, and the API passes sanrecap's portal configuration ID explicitly.

Because the Stripe key is a plain `String` in SSM (decided with the Google client secret: no Secrets Manager cost) and ends up in a Lambda environment variable, use only a restricted key.
