# 016: sanimmerse

The `sanimmerse` module builds the cloud of [sanimmerse](https://github.com/sancode-org/sanimmerse) (design: sanimmerse `docs/002-cloud.md`): an API Gateway HTTP API behind Cloudflare in front of one Lambda that serves the web app and the API, four DynamoDB tables, an S3 bucket for the agent downloads, and, when the Stripe settings exist, a billing Lambda fed by the Stripe partner event bus through SQS. When the Sign in with Apple key exists in SSM, the API Lambda gets it for the Sign in with Apple REST API. It does not run on k8s and is not part of `deploy all`.

Two stacks per environment, plus the shared `cognito` stack:

| Stack | Command | Contents |
|-------|---------|----------|
| `<prefix>-cognito` | `deploy cognito` | The shared user pool. Its app client `sanimmerse` (export `<prefix>-SanimmerseUserPoolClient`) is described below |
| `<prefix>-sanimmerse-certificate` | `deploy sanimmerse-certificate` | ACM certificate in ap-northeast-1 for `<env>-sanimmerse.sancode.dev` (prd `sanimmerse.sancode.dev`), export `<prefix>-SanimmerseCertificateArn` |
| `<prefix>-sanimmerse` | `deploy sanimmerse` | Everything else (below) |

## Resources

| Group | Resources |
|-------|-----------|
| Entry | API Gateway HTTP API `<prefix>-sanimmerse-http` with one `$default` route (no authorizer) to the `api` function (payload 2.0, integration timeout 30 s), stage `$default` (20 requests/s, burst 40, JSON access logs without headers, query strings or bodies). With the certificate: regional custom domain and API mapping, and the `execute-api` endpoint disabled |
| Compute | Lambda `<prefix>-sanimmerse-api` and, with Stripe, `<prefix>-sanimmerse-billing`: `nodejs24.x`, `arm64`, JSON logs, the same code directory, one IAM role each |
| Data | DynamoDB on demand: `<prefix>-sanimmerse-users` (`email`), `-data` (`userId` + `sk`), `-tokens` (`tokenHash`), `-shared-rules` (`key` + `userId`), all keys strings. `users` and `data` have point-in-time recovery; `users`, `data` and `tokens` have deletion protection. `shared-rules` is a copy of the sharing learners' `R#` items and can be rebuilt. Encryption at rest is DynamoDB's default; the stack has no KMS key |
| Downloads | S3 bucket (output `SanimmerseDownloadsBucket`): public access blocked, bucket owner enforced, SSE-S3, TLS only, incomplete multipart uploads aborted after 1 day |
| Billing (Stripe only) | `AWS::Events::EventBus` named after the Stripe partner event source, a rule (`source` prefix `aws.partner/stripe.com`) to SQS `<prefix>-sanimmerse-billing` (visibility timeout 180 s = 6 x the function timeout, `maxReceiveCount` 5) and DLQ `-billing-dlq` (14 days), both with SSE-SQS and TLS only; the queue policy allows only this rule |
| Logs | `/aws/lambda/<prefix>-sanimmerse-api`, `/aws/lambda/<prefix>-sanimmerse-billing`, `/aws/apigateway/<prefix>-sanimmerse-http-access`, 30 days |

## Lambda functions

| Function | Trigger | Handler | Timeout / memory | Environment |
|----------|---------|---------|------------------|-------------|
| `api` | HTTP API `$default` | `index.handler` | 29 s / 1024 MB | `USERS_TABLE`, `DATA_TABLE`, `TOKENS_TABLE`, `SHARED_RULES_TABLE`, `DOWNLOADS_BUCKET`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `AUTH_DOMAIN`, `APPLE_AUDIENCE`, `PUBLIC_URL`, `ALLOWED_SOURCES`; with Stripe also `STRIPE_SECRET_KEY`, `STRIPE_PORTAL_CONFIG`, `STRIPE_PRICE_PRO`, `STRIPE_PRICE_MAX`, `STRIPE_PRICE_ULTRA`; with the Apple key also `APPLE_KEY_ID`, `APPLE_TEAM_ID`, `APPLE_PRIVATE_KEY` |
| `billing` | Billing queue (batch 1, `ReportBatchItemFailures`, `MaximumConcurrency` 2) | `billing.handler` | 30 s / 256 MB | `USERS_TABLE`, `DATA_TABLE`, `STRIPE_PRICE_PRO`, `STRIPE_PRICE_MAX`, `STRIPE_PRICE_ULTRA` |

- `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID` and `AUTH_DOMAIN` are imported from the `cognito` stack (`<prefix>-UserPool`, `<prefix>-SanimmerseUserPoolClient`, `<prefix>-UserPoolDomain`: `dev-auth-k8s.sancode.dev`, prd `auth-k8s.sancode.dev`).
- `APPLE_AUDIENCE` is `dev.sancode.sanimmerse`, the iOS app's bundle ID, which Sign in with Apple puts in the identity token's `aud`. It is also the `client_id` of the Sign in with Apple REST API calls.
- `PUBLIC_URL` is `https://<custom domain>` with the certificate (`https://dev-sanimmerse.sancode.dev`, prd `https://sanimmerse.sancode.dev`) and the HTTP API's `execute-api` URL without it.
- `ALLOWED_SOURCES` is `cloudflare` with the custom domain and `any` without it. HTTP APIs take neither resource policies nor AWS WAF, so the app answers 403 to a request whose source address (`requestContext.http.sourceIp`) is outside Cloudflare's published ranges, as sanchoris and sanfreceipt do.
- The account's Lambda concurrency limit is 10 in dev and prd, and a function's reserved concurrency must leave 10 unreserved, so `billing` is capped by the event source's `MaximumConcurrency` 2 instead of reserved concurrency.

### IAM

| Function | Permissions |
|----------|-------------|
| `api` | On the four tables: `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchGetItem`, `BatchWriteItem`, `ConditionCheckItem` (transactions are authorized through the item actions). `Scan` on `shared-rules` only. `s3:GetObject` on the downloads bucket's objects and `s3:ListBucket` on the bucket (a missing key then answers 404) |
| `billing` | `GetItem`, `UpdateItem` on `users`; `GetItem`, `Query`, `PutItem`, `UpdateItem` on `data`; receive and delete on the billing queue |

Both write only to their own log group.

## Interface with the application

The stack fixes these names; the app (sanimmerse `apps/api`) matches them.

| Item | Contract |
|------|----------|
| Code | The directory `apps/api/dist/lambda` (or `SANIMMERSE_DIST`) with `index.mjs` and `billing.mjs`, each exporting `handler`, and the web app in `web/`. `deploy sanimmerse` stops when one of them is missing. The directory is zipped as it is (unzipped limit 250 MB) |
| `index.handler` | HTTP API payload 2.0 for every path: the web app, `/api/*` (`/api/config`, `/api/graphql`, `/api/events`, `/api/session`, billing) and `/download/*`. Logs one JSON object per line to stdout |
| `/download/*` | The api function redirects to a presigned `GetObject` URL of an object in `DOWNLOADS_BUCKET` |
| `billing.handler` | SQS event whose record body is the EventBridge envelope of one Stripe event (`detail` is the Stripe event object); returns `{ batchItemFailures }`. An event it cannot apply (unknown price) must fail so that it ends up in the DLQ |
| Tables | `users`: key `email` (lowercase). `data`: `userId` + `sk` (`A#<activity id>`, `R#<field>#<service>#<value>`, `S`, `O#<device>#<app>`, `T#<token hash>`). `tokens`: `tokenHash` (SHA-256 of the device token). `shared-rules`: `key` (`<field>#<service>#<value>`) + `userId`. No secondary index and no TTL |
| Cognito | ID tokens of the shared pool whose `aud` is `COGNITO_CLIENT_ID`; the web app uses the hosted UI at `https://<AUTH_DOMAIN>` with PKCE |
| Stripe | The portal session names `STRIPE_PORTAL_CONFIG` (the Stripe account is shared with other products). Without the Stripe variables the billing routes are unavailable |
| Apple | With `APPLE_KEY_ID`, `APPLE_TEAM_ID` and `APPLE_PRIVATE_KEY` the app exchanges the iOS app's Sign in with Apple authorization code for a refresh token at sign-in and revokes it when the account is deleted. Without them the sign-in skips the exchange |

## Cognito app client

The `cognito` stack has the app client `<prefix>-user-pool-client-sanimmerse`, exported as `<prefix>-SanimmerseUserPoolClient`: authorization code with PKCE, no secret, scopes `openid`, `email`, `profile`, identity providers Google and COGNITO, token revocation, ID and access tokens 60 minutes, refresh tokens 30 days.

| | dev | prd |
|---|---|---|
| Callback URLs | `https://dev-sanimmerse.sancode.dev/auth/callback`, `http://localhost:5173/auth/callback`, `sanimmerse://auth` | `https://sanimmerse.sancode.dev/auth/callback`, `sanimmerse://auth` |
| Sign-out URLs | `https://dev-sanimmerse.sancode.dev/`, `http://localhost:5173/`, `sanimmerse://auth` | `https://sanimmerse.sancode.dev/`, `sanimmerse://auth` |

The prd `cognito` stack also holds the sanplan, sanrecap and sanchoris clients, so update it through a change set and check that it changes only what is intended (`aws cloudformation create-change-set` with `UsePreviousValue` for the four parameters and the template from `target/cfn/`, then `describe-change-set`).

## Stripe

Billing is built from the Stripe settings in SSM. The six String parameters `/<prefix>/sanimmerse/stripe-{secret-key,price-pro,price-max,price-ultra,portal-config,event-source}` must all exist; `deploy sanimmerse` reads them with `aws ssm get-parameters` and, only then, adds the billing queue, bus, rule and function and the Stripe variables of `api`. The template references each with a versioned dynamic reference (`{{resolve:ssm:/<prefix>/sanimmerse/<name>:<version>}}`), so a new value reaches the functions on the next `deploy sanimmerse`.

| Name | Value | Variable | Functions |
|------|-------|----------|-----------|
| `stripe-secret-key` | Stripe restricted key (`rk_...`) | `STRIPE_SECRET_KEY` | api |
| `stripe-price-pro` | Price ID of `Sanimmerse Pro` (USD 20 a month, lookup key `sanimmerse_pro_monthly`) | `STRIPE_PRICE_PRO` | api, billing |
| `stripe-price-max` | Price ID of `Sanimmerse Max` (USD 100 a month, lookup key `sanimmerse_max_monthly`) | `STRIPE_PRICE_MAX` | api, billing |
| `stripe-price-ultra` | Price ID of `Sanimmerse Ultra` (USD 200 a month, lookup key `sanimmerse_ultra_monthly`) | `STRIPE_PRICE_ULTRA` | api, billing |
| `stripe-portal-config` | Customer portal configuration ID (`bpc_...`) for the three prices | `STRIPE_PORTAL_CONFIG` | api |
| `stripe-event-source` | Name of the Stripe partner event source (`aws.partner/stripe.com/ed_...`) | Stack parameter `StripeEventSourceName` | — (the stack's event bus and rule) |

```bash
aws ssm put-parameter --type String --name /dev-k8s/sanimmerse/stripe-price-pro --value '<price id>'
```

On the Stripe side (test mode on dev, live mode on prd): the three products with monthly prices, a portal configuration, a restricted key (Customers read, Products read, Prices read, Checkout Sessions write, Customer Portal write, Subscriptions write for the account deletion), and the event destination in the dashboard (Developers → Workbench → Webhooks → Add destination → Amazon EventBridge; events `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, snapshot payload; name `<prefix>-sanimmerse-billing`, the AWS account ID and `ap-northeast-1`). The destination's source name is also listed by `aws events list-event-sources`; it turns from `PENDING` to `ACTIVE` once the stack's bus exists. The values end up in the Lambda configuration in plain text, so the key is a restricted key.

## Sign in with Apple

The api function signs the client secret of the Sign in with Apple REST API (`https://appleid.apple.com/auth/token` and `/auth/revoke`) with a Sign in with Apple key of the team (Apple Developer → Keys, service Sign in with Apple, primary App ID `dev.sancode.sanimmerse`). The three String parameters `/<prefix>/sanimmerse/apple-{key-id,team-id,private-key}` must all exist; `deploy sanimmerse` reads them like the Stripe ones and, only then, adds the variables to `api` with versioned dynamic references. The same key serves dev and prd.

| Name | Value | Variable |
|------|-------|----------|
| `apple-key-id` | Key ID (10 characters) | `APPLE_KEY_ID` |
| `apple-team-id` | Team ID `Z4383RWSA2` | `APPLE_TEAM_ID` |
| `apple-private-key` | The `.p8` file as it is (PKCS #8 PEM, several lines) | `APPLE_PRIVATE_KEY` |

```bash
aws ssm put-parameter --type String --name /dev-k8s/sanimmerse/apple-private-key --value file://AuthKey_<key id>.p8
```

The key ends up in the Lambda configuration in plain text, as the Stripe key does; it can only sign Sign in with Apple requests of this team.

## Deploy

```bash
export AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1   # prd: DEPLOY_ENV=prd AWS_PROFILE=conao3.prd.k8s
```

Steps for a new environment, in order:

1. `deploy cognito` (the `sanimmerse` app client; on prd through a change set as above).
2. Certificate: `deploy sanimmerse-certificate`. The stack waits for DNS validation: read the record with `aws acm describe-certificate --certificate-arn <arn> --query 'Certificate.DomainValidationOptions[].ResourceRecord'` and add it to the `sancode.dev` zone in Cloudflare as a CNAME, DNS only.
3. Code, in sanimmerse: build `apps/api/dist/lambda`.
4. Stripe and Sign in with Apple in SSM (optional, above).
5. Stack:

   ```bash
   nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanimmerse
   ```

   It checks the code directory, reads the Stripe parameters, writes `target/cfn/sanimmerse.json`, runs `sam validate --lint`, and deploys. The custom domain is created when the certificate export exists (`SANIMMERSE_CERTIFICATE_ARN` overrides it).
6. Cloudflare: add a **proxied** CNAME `<env>-sanimmerse` (prd `sanimmerse`) to the output `SanimmerseApiDomainTarget`, SSL/TLS mode Full (strict). On dev, the Access application `Dev Sanimmerse Access` covers `dev-sanimmerse.sancode.dev`, and the bypass applications `Dev Sanimmerse API Bypass` (`dev-sanimmerse.sancode.dev/api`: the agents, the iOS app and its Shortcuts call it with tokens that the app checks) and `Dev Sanimmerse Download Bypass` (`dev-sanimmerse.sancode.dev/download`) let those paths through (agents-share `notes/cloudflare.md`).
7. Downloads: upload the agent executables to the bucket (`aws s3 cp <file> s3://<SanimmerseDownloadsBucket>/<key>`).

To deploy new code, build it and run step 5 again. A changed SSM value also needs step 5.

The templates can be generated and checked without AWS access:

```bash
nix develop --command clojure -M -m conao3.aws-infra-k8s template sanimmerse   # target/cfn/sanimmerse.json (Stripe and Apple) and sanimmerse-without-ssm.json (neither), placeholder CodeUri
nix develop --command sam validate --lint --template-file target/cfn/sanimmerse.json --region ap-northeast-1
```

## Rollback

- Code: build the earlier commit and run `deploy sanimmerse`.
- A failed stack update rolls back by itself. A failed first creation is deleted (`--on-failure DELETE`) but stops at `ROLLBACK_FAILED` because of the tables' deletion protection: turn it off (`aws dynamodb update-table --table-name <table> --no-deletion-protection-enabled` for `users`, `data` and `tokens`), delete the stack, and deploy again.
- Data: restore `users` or `data` to a new table with point-in-time recovery (`aws dynamodb restore-table-to-point-in-time --source-table-name <table> --target-table-name <table>-restore --restore-date-time <time>`, up to 35 days back) and copy the items that are needed back. The restored table is outside the stack; delete it afterwards.
- Billing: events that failed 5 times are in `<prefix>-sanimmerse-billing-dlq` (output `SanimmerseBillingDlqUrl`); after fixing the cause, move them back with `aws sqs start-message-move-task --source-arn <dlq arn>`.
- Removal: delete `<prefix>-sanimmerse` (deletion protection off first, the downloads bucket emptied), then `-certificate`; the `cognito` exports can go once nothing imports them. Remove the Cloudflare records, the Access applications and the Stripe event destination.

## Monitoring

The daily check by the operator, the same for every san* product: the depth of `<prefix>-sanimmerse-billing-dlq`, the `Errors` and `Throttles` of the `api` and `billing` functions, the HTTP API's 5xx count, and the `ERROR` logs in Logs Insights.

## Environments

| | dev | prd |
|---|---|---|
| `DEPLOY_ENV`, prefix, AWS profile | `dev`, `dev-k8s`, `conao3.dev.k8s` | `prd`, `prd-k8s`, `conao3.prd.k8s` |
| Host | `https://dev-sanimmerse.sancode.dev` (Cloudflare Access except `/api` and `/download`) | `https://sanimmerse.sancode.dev` |
| Cognito hosted UI | `dev-auth-k8s.sancode.dev` | `auth-k8s.sancode.dev` |
| SSM | `/dev-k8s/sanimmerse/` | `/prd-k8s/sanimmerse/` |
| Stripe | Test mode | Live mode |
| Sign in with Apple key | `U382H426M2` | `U382H426M2` |
