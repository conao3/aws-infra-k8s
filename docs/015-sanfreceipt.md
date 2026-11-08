# 015: sanfreceipt

The `sanfreceipt` module builds the AWS side of [sanfreceipt](https://github.com/sancode-org/sanfreceipt): an API Gateway HTTP API behind Cloudflare in front of one container image Lambda that serves both the API and the web app, a second Lambda from the same image for Stripe subscription events, and one DynamoDB table. It does not run on k8s and is not part of `deploy all`. It does not use Cognito: users sign in with freee OAuth and the app keeps its own sessions.

Three stacks per environment:

| Stack | Command | Contents |
|-------|---------|----------|
| `<prefix>-sanfreceipt-repository` | `deploy sanfreceipt-repository` | ECR repository `<prefix>-sanfreceipt` (export `<prefix>-SanfreceiptRepositoryUri`). Separate because a Lambda function needs its image to exist when it is created |
| `<prefix>-sanfreceipt-certificate` | `deploy sanfreceipt-certificate` | ACM certificate in ap-northeast-1 for `<env>-sanfreceipt.sancode.dev` (prd `sanfreceipt.sancode.dev`), export `<prefix>-SanfreceiptCertificateArn` |
| `<prefix>-sanfreceipt` | `deploy sanfreceipt` | Everything else (below) |

## Resources

| Group | Resources |
|-------|-----------|
| Image | ECR repository `<prefix>-sanfreceipt`: scan on push, immutable tags, untagged images expire after 1 day, the last 5 images are kept for a rollback; the repository policy lets only `lambda.amazonaws.com` for functions named `<prefix>-sanfreceipt-*` pull |
| Entry | API Gateway HTTP API `<prefix>-sanfreceipt-http` with one `$default` route (no authorizer) to the `api` function (payload 2.0, integration timeout 30 s, the HTTP API maximum), stage `$default` (20 requests/s, burst 40, JSON access logs without cookies, query strings or bodies). With the certificate: regional custom domain and API mapping, and the `execute-api` endpoint disabled |
| Compute | Lambda `<prefix>-sanfreceipt-api` and `<prefix>-sanfreceipt-billing`, both `PackageType: Image` from the same image tag, `arm64`, JSON logs, one IAM role each |
| Data | DynamoDB table `<prefix>-sanfreceipt`: on-demand, key `pk` (S) + `sk` (S), TTL on `ttl`, point-in-time recovery, deletion protection. Encryption at rest is DynamoDB's default (AWS owned key); the stack has no KMS key |
| Billing | `AWS::Events::EventBus` named after the Stripe partner event source, a rule (`source` prefix `aws.partner/stripe.com`) to SQS `<prefix>-sanfreceipt-billing` (visibility timeout 180 s = 6 x the function timeout, `maxReceiveCount` 5) and DLQ `-billing-dlq` (14 days), both with TLS only and no SSE (queue policy in 012-security.md); the queue policy allows only this rule |
| Logs | `/aws/lambda/<prefix>-sanfreceipt-api`, `/aws/lambda/<prefix>-sanfreceipt-billing`, `/aws/apigateway/<prefix>-sanfreceipt-http-access`, 30 days |

The browser talks to freee's API directly for deals, partners and receipt metadata; those requests never reach AWS. The backend calls freee only for OAuth and for the receipt file it reads, Vercel AI Gateway for paid readings, Claude Code with the user's own subscription token for subscription readings, and the National Tax Agency Web-API for registration numbers. Lambda reaches them over the internet (no VPC).

## Lambda functions

| Function | Trigger | Image command | Timeout / memory | Environment | Table permissions |
|----------|---------|---------------|------------------|-------------|-------------------|
| `api` | HTTP API `$default` | `lambda/index.handler` | 60 s / 2048 MB | `SANFRECEIPT_APP_ORIGIN`, `SANFRECEIPT_TABLE`, `SANFRECEIPT_ALLOWED_SOURCES`, `SANFRECEIPT_ADMIN_COMPANY_IDS`, `FREEE_CLIENT_ID`, `FREEE_CLIENT_SECRET`, `FREEE_ACCOUNTS_BASE`, `FREEE_API_BASE`, `NTA_API_BASE`, `NTA_APPLICATION_ID`, `AI_GATEWAY_BASE_URL`, `AI_GATEWAY_API_KEY`, `AI_GATEWAY_MODEL`, `STRIPE_API_BASE`, `STRIPE_SECRET_KEY`, `STRIPE_PRICE_PRO`, `STRIPE_PRICE_MAX`, `STRIPE_PRICE_ULTRA`, `STRIPE_PORTAL_CONFIG` | `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `ConditionCheckItem` |
| `billing` | Billing queue (batch 1, `ReportBatchItemFailures`) | `lambda/billing.handler` | 30 s / 256 MB | `SANFRECEIPT_TABLE`, `STRIPE_PRICE_PRO`, `STRIPE_PRICE_MAX`, `STRIPE_PRICE_ULTRA` | Same as `api` |

- `SANFRECEIPT_APP_ORIGIN` is `https://<custom domain>` with the certificate and the HTTP API's `execute-api` URL without it.
- `SANFRECEIPT_ALLOWED_SOURCES` is `cloudflare` with the custom domain and `any` without it. HTTP APIs take neither resource policies nor AWS WAF, so the app answers 403 to a request whose source address (`requestContext.http.sourceIp`) is outside Cloudflare's published ranges, as sanchoris does ([014](014-sanchoris.md), "Custom domains behind Cloudflare").
- The fixed bases: `FREEE_ACCOUNTS_BASE` `https://accounts.secure.freee.co.jp`, `FREEE_API_BASE` `https://api.freee.co.jp`, `NTA_API_BASE` `https://web-api.invoice-kohyo.nta.go.jp`, `AI_GATEWAY_BASE_URL` `https://ai-gateway.vercel.sh/v4/ai`, `STRIPE_API_BASE` `https://api.stripe.com/v1`.
- The sizes and the timeout of `api` follow sanfreceipt README "Deployment (AWS Lambda)": a reading with a Claude subscription runs Claude Code in the function. The HTTP API answers the browser within 30 s; a reading takes 5 to 10 s.
- The app writes items in transactions (`TransactWriteItems`); IAM authorizes a transaction through the item actions above.
- `AI_GATEWAY_MODEL` is the stack parameter `AiGatewayModel` (default `anthropic/claude-opus-5.5`, `SANFRECEIPT_AI_GATEWAY_MODEL` overrides it).
- The other values come from SSM (next section).

## Interface with the application

The stack fixes these names; the app (sanfreceipt `apps/api`, its DynamoDB store and its container image) matches them.

| Item | Contract |
|------|----------|
| Image | Built by sanfreceipt `scripts/build-image.sh <image>`: `linux/arm64`, base `public.ecr.aws/lambda/nodejs:24`, the bundles in `/var/task/lambda` (`index.mjs`, `billing.mjs`), the web app in `/var/task/web`, and Claude Code of the installed Agent SDK version in `/var/task/claude-code/claude`. The image sets `SANFRECEIPT_WEB_DIR` and `SANFRECEIPT_CLAUDE_PATH` itself; the stack sets the command of each function with `ImageConfig.Command` |
| Image tag | `git-<first 12 hex digits of the commit>`, pushed to `<account>.dkr.ecr.ap-northeast-1.amazonaws.com/<prefix>-sanfreceipt`. Tags are immutable, so build a new commit for a new image |
| `lambda/index.handler` | HTTP API payload 2.0 for every path (the API under `/api`: `/api/graphql`, `/api/oauth/*`, `/api/cli/*`; the web app for every other path, `/cli` included). Logs one JSON object per line to stdout |
| `lambda/billing.handler` | SQS event whose record body is the EventBridge envelope of one Stripe event (`detail` is the Stripe event object); returns `{ batchItemFailures }`. An event it cannot apply (unknown price) must fail so that it ends up in the DLQ |
| Table | Name in `SANFRECEIPT_TABLE`. Key attributes `pk` and `sk`, both strings. No secondary index: lookups by another key (freee user ID to user, user to sessions) are items of their own or `Query` on a `pk`. TTL attribute `ttl` (a reserved word: `#ttl` in expressions) in epoch seconds (a number) on OAuth states, sessions, device codes, CLI tokens, read marks and Stripe event IDs, apart from the app's own `expiresAt` values in milliseconds; items that must stay (users, freee tokens, settings) have no `ttl`. The item layout is in sanfreceipt README "Deployment (AWS Lambda)". DynamoDB deletes expired items within about two days, so reads also check the expiry |
| Store selection | The Lambda entry points build the DynamoDB store from `SANFRECEIPT_TABLE`; `SANFRECEIPT_STORE_FILE` and `SANFRECEIPT_API_PORT` are not set in Lambda |
| Stripe | Checkout puts the freee company ID in `client_reference_id` and in `subscription_data.metadata.company_id`, so the subscription events carry it. The portal session names `STRIPE_PORTAL_CONFIG` (the Stripe account is shared with other products) |

## SSM parameters

All are `String` parameters under `/<prefix>/sanfreceipt/`, created by hand before `deploy sanfreceipt`. `deploy sanfreceipt` reads them with `aws ssm get-parameters` and stops with the list of missing names. The template references each with a versioned dynamic reference (`{{resolve:ssm:/<prefix>/sanfreceipt/<name>:<version>}}`), so a new value changes the template and reaches the functions on the next `deploy sanfreceipt`. The values end up in the Lambda configuration in plain text (the decision for sanfreceipt: no KMS, no Secrets Manager), so the Stripe key is a restricted key.

| Name | Type | Value | Variable | Functions |
|------|------|-------|----------|-----------|
| `freee-client-id` | String | Client ID of the freee app | `FREEE_CLIENT_ID` | api |
| `freee-client-secret` | String | Client secret of the freee app | `FREEE_CLIENT_SECRET` | api |
| `ai-gateway-api-key` | String | Vercel AI Gateway API key for paid readings | `AI_GATEWAY_API_KEY` | api |
| `nta-application-id` | String | National Tax Agency Web-API application ID | `NTA_APPLICATION_ID` | api |
| `admin-company-ids` | String | Comma-separated freee company IDs on the Admin plan (Sancode: `12298327`) | `SANFRECEIPT_ADMIN_COMPANY_IDS` | api |
| `stripe-secret-key` | String | Stripe restricted key (`rk_...`) | `STRIPE_SECRET_KEY` | api |
| `stripe-price-pro` | String | Price ID of `Sanfreceipt Pro` (USD 20 a month, lookup key `sanfreceipt_pro`) | `STRIPE_PRICE_PRO` | api, billing |
| `stripe-price-max` | String | Price ID of `Sanfreceipt Max` (USD 100 a month, lookup key `sanfreceipt_max`) | `STRIPE_PRICE_MAX` | api, billing |
| `stripe-price-ultra` | String | Price ID of `Sanfreceipt Ultra` (USD 200 a month, lookup key `sanfreceipt_ultra`) | `STRIPE_PRICE_ULTRA` | api, billing |
| `stripe-portal-config` | String | Customer portal configuration ID (`bpc_...`, `metadata[app]=sanfreceipt`) | `STRIPE_PORTAL_CONFIG` | api |
| `stripe-event-source` | String | Name of the Stripe partner event source (`aws.partner/stripe.com/ed_...`) | Stack parameter `StripeEventSourceName` | — (the stack's event bus and rule) |

```bash
aws ssm put-parameter --type String --name /dev-k8s/sanfreceipt/freee-client-id --value '<client id>'
```

## Deploy

```bash
export AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1   # prd: DEPLOY_ENV=prd AWS_PROFILE=conao3.prd.k8s
```

Steps for a new environment, in order:

1. Repository: `nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanfreceipt-repository`.
2. Image, in sanfreceipt:

   ```bash
   R=$(aws cloudformation list-exports --query "Exports[?Name=='dev-k8s-SanfreceiptRepositoryUri'].Value | [0]" --output text)
   TAG=git-$(git rev-parse --short=12 HEAD)
   aws ecr get-login-password | docker login --username AWS --password-stdin ${R%%/*}
   scripts/build-image.sh $R:$TAG
   docker push $R:$TAG
   ```

   The script builds with `--provenance=false`, which keeps the push a single image manifest as Lambda requires.
3. Certificate: `deploy sanfreceipt-certificate`. The stack waits for DNS validation: read the record with `aws acm describe-certificate --certificate-arn <arn> --query 'Certificate.DomainValidationOptions[].ResourceRecord'` and add it to the `sancode.dev` zone in Cloudflare as a CNAME, DNS only.
4. Stripe (test mode on dev, live mode on prd): products `Sanfreceipt Pro`, `Sanfreceipt Max`, `Sanfreceipt Ultra` with monthly USD prices (20, 100, 200) and a customer portal configuration for the three prices, both made by sanfreceipt `scripts/stripe-setup.mjs`, a restricted key (Customers read, Products read, Prices read, Checkout Sessions write, Customer Portal write), and the event destination in the dashboard (Developers → Workbench → Webhooks → Add destination → Amazon EventBridge; events `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, snapshot payload; name `<prefix>-sanfreceipt-billing`, the AWS account ID and `ap-northeast-1`). The destination's source name is also listed by `aws events list-event-sources`.
5. SSM: put the eleven parameters above.
6. Stack, with the image tag of step 2:

   ```bash
   SANFRECEIPT_IMAGE_TAG=$TAG nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanfreceipt
   ```

   It checks that the image tag exists in ECR and that the SSM parameters exist, writes `target/cfn/sanfreceipt.json`, runs `sam validate --lint`, and deploys. The custom domain is created when the certificate export exists (`SANFRECEIPT_CERTIFICATE_ARN` overrides it). The partner event source turns from `PENDING` to `ACTIVE` once the bus exists.
7. Cloudflare: add a **proxied** CNAME `<env>-sanfreceipt` (prd `sanfreceipt`) to the output `SanfreceiptApiDomainTarget`, SSL/TLS mode Full (strict). On dev, add the Access application `Dev Sanfreceipt Access` for `dev-sanfreceipt.sancode.dev` and the bypass application `Dev Sanfreceipt CLI Bypass` for `dev-sanfreceipt.sancode.dev/api/cli`, the device flow endpoints that sancli calls without a browser and that the app authenticates with the device code and the one-time access token (agents-share `notes/cloudflare.md`). The approval page `/cli` stays behind Access.
8. freee: add `https://<host>/api/oauth/callback` to the callback URLs of the freee app.

To deploy new code, push a new tag (step 2) and run step 6 with it. A changed SSM value also needs step 6.

The template can be generated and checked without AWS access:

```bash
nix develop --command clojure -M -m conao3.aws-infra-k8s template sanfreceipt   # placeholder image URI, unversioned SSM references
nix develop --command sam validate --lint --template-file target/cfn/sanfreceipt.json --region ap-northeast-1
```

## Rollback

- Code: run `deploy sanfreceipt` with an earlier `SANFRECEIPT_IMAGE_TAG`. The repository keeps the last 5 images; the image of the running functions must stay among them, so do not push more than 4 images without deploying one.
- A failed stack update rolls back by itself. A failed first creation is deleted (`--on-failure DELETE`) but stops at `ROLLBACK_FAILED` because of the table's deletion protection: turn it off (`aws dynamodb update-table --table-name <prefix>-sanfreceipt --no-deletion-protection-enabled`), delete the stack, and deploy again.
- Data: restore the table to a new table with point-in-time recovery (`aws dynamodb restore-table-to-point-in-time --source-table-name <prefix>-sanfreceipt --target-table-name <prefix>-sanfreceipt-restore --restore-date-time <time>`, up to 35 days back) and copy the items that are needed back into the stack's table. The restored table is outside the stack; delete it afterwards.
- Billing: events that failed 5 times are in `<prefix>-sanfreceipt-billing-dlq` (output `SanfreceiptBillingDlqUrl`); after fixing the cause, move them back with `aws sqs start-message-move-task --source-arn <dlq arn>`.
- Removal: delete `<prefix>-sanfreceipt` (deletion protection off first), then `-certificate`, then `-repository` after emptying the repository (`aws ecr batch-delete-image`). Remove the Cloudflare records, the Access applications, the Stripe event destination, and the freee callback URL.

## Monitoring

The service is small, so monitoring is a daily check by the operator, the same for every san* product: once a day, look at the depth of `<prefix>-sanfreceipt-billing-dlq`, the `Errors` and `Throttles` of the `api` and `billing` functions, and the HTTP API's 5xx count, and query the `ERROR` logs in Logs Insights. This daily check is how failures of the stack are noticed.

External services that email operational notices about sanfreceipt (Stripe, freee, Vercel, the National Tax Agency Web-API, AWS) send them to `alerm+sanfreceipt@sancode.dev`. Register this address wherever sanfreceipt's accounts or apps ask for a notification or technical contact address.

## Lambda concurrency

The dev and prd accounts allow 1000 concurrent Lambda executions in total, shared with the other products, and both functions draw from it; a burst of readings that hits the account limit shows as `api` Throttles in the daily check.

## Environments

| | dev | prd |
|---|---|---|
| `DEPLOY_ENV`, prefix, AWS profile | `dev`, `dev-k8s`, `conao3.dev.k8s` | `prd`, `prd-k8s`, `conao3.prd.k8s` |
| Host | `https://dev-sanfreceipt.sancode.dev` (Cloudflare Access) | `https://sanfreceipt.sancode.dev` |
| SSM | `/dev-k8s/sanfreceipt/` | `/prd-k8s/sanfreceipt/` |
| Stripe | Test mode | Live mode |

## Cost estimate

Monthly USD in ap-northeast-1, before tax, per environment. Unit prices as in [014](014-sanchoris.md) ("Cost estimate"). Light use means one or two companies reading about 500 receipts a month (about 8 s of `api` at 2 GB per reading) and 30,000 HTTP requests.

| Item | Unit price | Idle | Light use |
|------|------------|------|-----------|
| API Gateway HTTP API | $1.29 per million requests | 0 | 0.04 |
| Lambda (ARM) | $0.20 per million requests, $0.0000133334 per GB-s (1 million requests and 400,000 GB-s free) | 0 | 0 (about 20,000 GB-s) |
| DynamoDB on-demand | $0.715 per million WRU, $0.1425 per million RRU, 25 GB free, PITR $0.228 per GB-month | 0 | 0.02 |
| SQS standard | $0.40 per million requests (1 million free) | 0 to 0.2 (Lambda polling the empty billing queue) | 0 to 0.2 |
| ECR | $0.10 per GB-month | 0.05 to 0.25 (one to five images of about 0.5 GB) | 0.05 to 0.25 |
| CloudWatch Logs | $0.76 per GB ingested (5 GB free) | 0 | 0 |
| EventBridge, SSM standard parameters, ACM | Free at this volume | 0 | 0 |
| **Total** | | **about 0.05 to 0.45** | **about 0.1 to 0.5** |

Outside AWS: Vercel AI Gateway bills the model tokens of paid readings (Claude Opus 5.5 at $4 / $20 per million input / output tokens; a measured reading takes 5,300 input tokens for a one-page PDF and 8,300 for the largest image, with up to 300 output tokens, so USD 0.02 to 0.04 per receipt; the plans count USD 0.05 a reading); readings on a user's own Claude subscription cost Sancode nothing. Cloudflare (Free plan, Access for fewer than 50 users) adds nothing. Traffic out to the internet stays within the 100 GB a month that AWS does not charge.
