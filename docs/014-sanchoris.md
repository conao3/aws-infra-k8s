# 014: sanchoris

The `sanchoris` module builds the AWS side of [sanchoris](https://github.com/conao3/sanchoris): a serverless control plane (API Gateway, Lambda, DynamoDB, S3, SQS, EventBridge Scheduler) and ECS Fargate for Workspaces. It does not run on k8s and is not part of `deploy all`. The contract with the application is sanchoris `docs/architecture.md`; the DynamoDB tables come from sanchoris `infra/tables.json`, so the table definitions are never retyped here.

## Resources

| Group | Resources |
|-------|-----------|
| Tables | One `AWS::DynamoDB::Table` per entry of `infra/tables.json` (`properties` as is, name `<prefix>-sanchoris-<role>`). Today: `main` (stream `NEW_IMAGE`, TTL `expires_at`, two GSIs) and `audit` (append-only). Both on-demand, PITR, deletion protection |
| Objects | KMS key (`alias/<prefix>-sanchoris`, rotation on) and two S3 buckets with SSE-KMS under it, public access blocked, TLS only, incomplete multipart uploads aborted after 1 day: `docs` (offloaded attributes, kept, S3 Bucket Key on) and `records` (session records and audit exports, expire after 90 days, the longest `retentionDays` in `infra/plans.json`; S3 Bucket Key off, because the app puts the tenant id in each object's encryption context). Run log lines live in DynamoDB |
| Work queue | SQS FIFO `<prefix>-sanchoris-work.fifo` (content-based deduplication, visibility timeout 1800 s = 6 x the worker timeout, `maxReceiveCount` 5) and DLQ `-work-dlq.fifo`; standard queue `-relay-dlq` for stream records the relay gave up on |
| Timers | EventBridge Scheduler group `<prefix>-sanchoris`, the role that lets schedules send to the work queue, and the four rate schedules of `docs/architecture.md` ("Rate schedules"). The relay creates one-time schedules in the same group |
| HTTP API | API Gateway HTTP API (`$default` stage, 20 requests/s, burst 40, JSON access logs). Routes in `http-routes`: `ANY /api/{proxy+}` with the Cognito JWT authorizer (issuer = the shared pool, audience = the sanchoris app client) and `$default` without an authorizer for `/webhooks/*`, `/auth/github*`, `/health`, and the web app, which the api function serves from the frontend build embedded in its binary (the api checks webhook signatures, the OAuth `state`, and the bearer token itself) |
| Run channel | API Gateway WebSocket API (stage `prod`, `$connect` / `$disconnect` / `$default` to the channel Lambda, JSON access logs). `$connect` has a REQUEST authorizer (the channel-authorizer Lambda, identity source `route.request.header.Authorization`) that verifies the Run token |
| Compute | Five Lambda functions (`functions`, below), event source mappings for the work queue and for every table listed in `streamFilters` |
| Workspaces | VPC `10.1.0.0/16` with a public subnet (for the network relay) and a Workspace subnet without a route to the internet, S3 gateway endpoint, optional interface endpoints, ECR repository `<prefix>-sanchoris-workspace` (scan on push, last 10 images), ECS cluster `<prefix>-sanchoris` (Fargate and Fargate Spot, Container Insights off), the Workspace task definition (ARM64, 2 vCPU / 4 GiB, user `1000:1000`, `stopTimeout` 120 s), its execution role and log group, and security groups for Workspaces, the relay, and the endpoints |
| Logs | One log group per Lambda, the Workspace log group, and the two access log groups, all with 30 days retention |

## Lambda functions

The function list is data (`functions` in `src/conao3/aws_infra_k8s/sanchoris.clj`): each entry has a name, timeout, memory, the environment groups (`env-vars`), the secrets it reads from SSM (`secrets`), and the permission groups (`grant-statements`). The triggers name their function by id (`http-function`, `channel-function`, `channel-authorizer-function`, `work-function`, `stream-function`). Code is `target/lambda/<name>/bootstrap.zip` in sanchoris, runtime `provided.al2023`, handler `bootstrap`, `arm64`, JSON logs. The table matches sanchoris `docs/architecture.md` ("Lambda functions").

| Function | Trigger | Timeout / memory | Environment | Secrets (SSM) | Permissions |
|----------|---------|------------------|-------------|---------------|-------------|
| `api` | HTTP API (payload 2.0): `ANY /api/{proxy+}` (JWT) and `$default` | 29 s / 512 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_PUBLIC_URL`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `GITHUB_URL`, `GITHUB_API_URL`, `STRIPE_API_URL`, `STRIPE_PORTAL_CONFIGURATION`, `SANCHORIS_RECORDS_STORE=s3`, `SANCHORIS_RECORDS_BUCKET`, `SANCHORIS_RECORDS_KMS_KEY` | `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_CLIENT_SECRET`, `GITHUB_APP_SLUG`, `GITHUB_WEBHOOK_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `TASK_TOKEN_SIGNING_KEY`, `OAUTH_STATE_SIGNING_KEY`, `SANCHORIS_CREDENTIAL_KEY` | tables, docs, records + the stack key, `ssm:GetParameters` on its parameters |
| `channel-authorizer` | REQUEST authorizer of the WebSocket API's `$connect` | 10 s / 256 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH` | `RUN_TOKEN_SIGNING_KEY` | `dynamodb:GetItem` on `main`, docs read, `ssm:GetParameters` on its parameter |
| `channel` | WebSocket API `$connect`, `$default`, `$disconnect` | 29 s / 256 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_RECORDS_STORE=s3`, `SANCHORIS_RECORDS_BUCKET`, `SANCHORIS_RECORDS_KMS_KEY`, `SANCHORIS_CHANNEL_ENDPOINT` | `RUN_TOKEN_SIGNING_KEY` | tables, docs, records + the stack key, `execute-api:ManageConnections` (POST and DELETE on `@connections` of the stage), `ssm:GetParameters` on its parameter |
| `worker` | Work queue (batch 10, `ReportBatchItemFailures`, `MaximumConcurrency` = `WorkerMaxConcurrency`, default 5) | 300 s / 512 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_RECORDS_STORE=s3`, `SANCHORIS_RECORDS_BUCKET`, `SANCHORIS_RECORDS_KMS_KEY`, `SANCHORIS_CHANNEL_ENDPOINT`, `GITHUB_API_URL`, `SANCHORIS_ECS_CLUSTER`, `SANCHORIS_ECS_TASK_DEFINITION`, `SANCHORIS_ECS_CONTAINER`, `SANCHORIS_ECS_SUBNETS`, `SANCHORIS_ECS_SECURITY_GROUPS`, `SANCHORIS_CONTROL_URL`, `SANCHORIS_GIT_URL` | `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `RUN_TOKEN_SIGNING_KEY` | tables, docs, records + the stack key, `ManageConnections`, receive/delete/change visibility on the work queue, `ecs:RunTask` of the Workspace family on this cluster, stop/describe/tag its tasks, pass the execution role, `ssm:GetParameters` on its parameters |
| `relay` | DynamoDB Streams of `main` (filter from `streamFilters`, batch 100, bisect on error, 10 retries, failures to `relay-dlq`) | 60 s / 256 MB | `SANCHORIS_WORK_QUEUE_URL`, `SANCHORIS_WORK_QUEUE_ARN`, `SANCHORIS_SCHEDULE_GROUP`, `SANCHORIS_SCHEDULER_ROLE_ARN` | — | stream read, send to the work queue and `relay-dlq`, create/delete/get schedules in the group, pass the scheduler role |

`SANCHORIS_SECRETS_PATH` is `/<prefix>/sanchoris/`, `SANCHORIS_RECORDS_KMS_KEY` the stack key's ARN, and `SANCHORIS_CHANNEL_ENDPOINT` the WebSocket stage's `https://<api id>.execute-api.<region>.amazonaws.com/prod` (output `SanchorisChannelEndpoint`). Table permissions follow `infra/tables.json`: tables with `appendOnly` get `PutItem`, `GetItem`, `Query`, `ConditionCheckItem` and an explicit Deny of `appendOnlyDeniedActions`; the other tables get item reads and writes with an explicit Deny of writes whose leading key is in `readOnlyLeadingKeys` (`PLANS`, written at deploy time by an administrator). Every bucket permission comes with `s3:ListBucket` (a missing key reads as 404) and `kms:GenerateDataKey` / `kms:Decrypt` on the stack key; the app encrypts records with that key and `{"sanchoris:tenant": "<tenant id>"}` as the encryption context, so one key serves every tenant and CloudTrail names the tenant of each data key.

## Workspace egress

A Workspace reaches the internet only through the network relay (`HTTPS_PROXY`, sanchoris `implementation.md` 4.4). The Workspace security group allows egress to the relay security group on port 3128, to the endpoint security group on 443, and to the S3 prefix list on 443 (image layers); nothing else. The task definition has no task role, so the agent gets no AWS credentials. Fargate itself still needs ECR and CloudWatch Logs from inside the subnet, which the VPC endpoints provide: their policies allow only principals of this account, and the S3 gateway endpoint allows only `GetObject` on the ECR layer bucket.

| Egress option for the Workspace subnet | Fixed cost (ap-northeast-1, per month) | Domain control |
|-----------|-----------------------------------------|----------------|
| Interface endpoints `ecr.api`, `ecr.dkr`, `logs` in one AZ + S3 gateway endpoint (this module) | 3 x $0.014/h = $30.66, plus $0.01/GB | None needed: the subnet has no internet route; the relay decides |
| NAT gateway | $0.062/h = $45.26, plus $0.062/GB | Needs the relay or AWS Network Firewall on top |
| Public subnet + public IP per task | $0.005/h per running task | Security group only; the agent could bypass the relay |

The interface endpoints are the largest fixed cost, so they are created only with `EnableWorkspaceEndpoints=true` (`SANCHORIS_ENABLE_WORKSPACES=true` at deploy). Without them the control plane runs, but `RunTask` fails to pull the image. The Workspace subnet is in one AZ (the first of `Fn::GetAZs`) so that one set of endpoints serves it.

### Network relay (next step)

The relay is not in this stack. It goes in the public subnet with the relay security group (ingress 3128 from Workspaces, egress 80/443 to the internet). It is an authenticating forward proxy: the Workspace authenticates with its Run token, the relay applies the operator deny list, then the Session's `Network.Allow`, and for `github.com` / `api.github.com` adds the Run's GitHub token (TLS interception with a CA the Workspace image trusts), limited to `GitHub.Repos`. The provider APIs and the Run channel go through the same relay on platform-defined rules. A Fargate service of 0.25 vCPU / 0.5 GB on ARM with a public IP costs about $12.7 per month. DNS queries from the Workspace go to the VPC resolver; Route 53 Resolver DNS Firewall ($0.60 per million queries) closes DNS as a side channel.

## Prerequisites

1. `deploy cognito` with the sanchoris app client (export `<prefix>-SanchorisUserPoolClient`): authorization code, no secret, Google only, callback `<origin>/auth/callback` and logout `<origin>/login` for `https://sanchoris.localhost` (portless on 443) and `https://dev-sanchoris.sancode.dev` (prd: `https://sanchoris.sancode.dev`). The stack imports `<prefix>-UserPool` and this client.
2. SSM parameters (String or SecureString) under `/<prefix>/sanchoris/`. `deploy sanchoris` checks them with `aws ssm get-parameters` and stops with the list of missing names. The template holds only the path (`SANCHORIS_SECRETS_PATH`); each function reads its parameters with `ssm:GetParameters` at cold start, so the values stay out of the template, the stack, and the Lambda configuration, and the `api` function stays under Lambda's 4 KB limit on environment variables with the GitHub App key.

   | Name | Variable |
   |------|----------|
   | `github-app-id` | `GITHUB_APP_ID` |
   | `github-app-private-key` | `GITHUB_APP_PRIVATE_KEY` (PEM) |
   | `github-app-client-id` | `GITHUB_APP_CLIENT_ID` |
   | `github-app-client-secret` | `GITHUB_APP_CLIENT_SECRET` |
   | `github-app-slug` | `GITHUB_APP_SLUG` |
   | `github-webhook-secret` | `GITHUB_WEBHOOK_SECRET` |
   | `stripe-secret-key` | `STRIPE_SECRET_KEY` (restricted key) |
   | `stripe-webhook-secret` | `STRIPE_WEBHOOK_SECRET` |
   | `run-token-signing-key` | `RUN_TOKEN_SIGNING_KEY` (32+ bytes) |
   | `task-token-signing-key` | `TASK_TOKEN_SIGNING_KEY` (32+ bytes) |
   | `oauth-state-signing-key` | `OAUTH_STATE_SIGNING_KEY` (32+ bytes) |
   | `credential-key` | `SANCHORIS_CREDENTIAL_KEY` (`openssl rand -base64 32`) |

   ```bash
   aws ssm put-parameter --type SecureString --name /dev-k8s/sanchoris/run-token-signing-key --value "$(openssl rand -hex 32)"
   ```

   The same path holds four String parameters that are not secret, and `deploy sanchoris` checks them too:

   | Name | Read by |
   |------|---------|
   | `stripe-portal-config` | The stack parameter `StripePortalConfiguration` (`AWS::SSM::Parameter::Value<String>`), resolved into the `api` function's `STRIPE_PORTAL_CONFIGURATION`. The Stripe account is shared with other products, so every customer portal session names this configuration (`bpc_...`). A new value takes effect on the next stack update |
   | `stripe-price-starter`, `stripe-price-pro`, `stripe-price-max` | Seeding `PLANS` after a deploy (below) |

3. sanchoris checked out at `~/ghq/github.com/conao3/sanchoris` (or `SANCHORIS_DIR`) with `infra/tables.json` and the built functions `target/lambda/{api,channel-authorizer,channel,worker,relay}/bootstrap.zip` (`VITE_COGNITO_DOMAIN=<Hosted UI domain> VITE_COGNITO_CLIENT_ID=<SanchorisUserPoolClient> nix develop -c pnpm build:lambda`; the api zip embeds the frontend built with those values).
4. The API Gateway account setting has a CloudWatch Logs role (the WebSocket stage writes access logs through it). The `sanrecap` stack sets it in dev and prd.

## Deploy

```bash
export AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy cognito
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanchoris-certificate
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanchoris
```

`deploy sanchoris` runs `sam validate --lint` first and passes these parameters:

| Parameter | Source |
|-----------|--------|
| `ApiDomainName`, `ChannelDomainName`, `CertificateArn` | `<env>-sanchoris.sancode.dev`, `<env>-sanchoris-channel.sancode.dev` (prd without the env) and export `<prefix>-SanchorisCertificateArn`, or `SANCHORIS_CERTIFICATE_ARN`. Without the certificate no custom domain is created |
| `S3PrefixListId` | `aws ec2 describe-prefix-lists` (`com.amazonaws.ap-northeast-1.s3`) |
| `EnableWorkspaceEndpoints` | `SANCHORIS_ENABLE_WORKSPACES=true`; default `false` |
| `WorkspaceImageTag` | `SANCHORIS_WORKSPACE_IMAGE_TAG`; default `latest` |
| `WorkerMaxConcurrency` | `SANCHORIS_WORKER_MAX_CONCURRENCY`; default 5 |

After each `deploy sanchoris`, seed the plan catalog. The functions' role denies writes to the `PLANS` partition, so an administrator runs sanchoris's `sanchoris-dynamo-create` against the stack's tables; it writes `infra/plans.json` with the Stripe price of each paid plan and stops when one is missing:

```bash
price() { aws ssm get-parameter --name "/dev-k8s/sanchoris/stripe-price-$1" --query Parameter.Value --output text; }
SANCHORIS_TABLE_MAIN=dev-k8s-sanchoris-main SANCHORIS_TABLE_AUDIT=dev-k8s-sanchoris-audit \
  SANCHORIS_STRIPE_PRICE_STARTER=$(price starter) \
  SANCHORIS_STRIPE_PRICE_PRO=$(price pro) \
  SANCHORIS_STRIPE_PRICE_MAX=$(price max) \
  nix develop -c cargo run -q -p sanchoris-core --bin sanchoris-dynamo-create
```

Delete in the reverse order (sanchoris, then sanchoris-certificate and cognito). The tables have deletion protection: turn it off (`aws dynamodb update-table --no-deletion-protection-enabled`) before deleting the stack, and empty the buckets and the ECR repository first.

The dev account allows 10 concurrent Lambda executions in total, and the sanrecap worker may take 5 of them; with `WorkerMaxConcurrency` 5 the sanchoris worker can take the rest. Use `SANCHORIS_WORKER_MAX_CONCURRENCY=2` in dev until the quota is raised.

## Custom domains behind Cloudflare

`deploy sanchoris-certificate` creates one ACM certificate in ap-northeast-1 for both host names (`DomainName` plus one subject alternative name), because an API Gateway custom domain maps either HTTP/REST APIs or WebSocket APIs, so the Run channel has its own host name. Add both DNS validation records to the `sancode.dev` zone (DNS only), as in [013](013-sanrecap.md).

After `deploy sanchoris`, add two **proxied** CNAMEs: `<env>-sanchoris` to `SanchorisApiDomainTarget` and `<env>-sanchoris-channel` to `SanchorisChannelDomainTarget`. With the custom domains the HTTP API's `execute-api` endpoint is disabled. The outputs `SanchorisPublicUrl` (`SANCHORIS_PUBLIC_URL`) and `SanchorisControlUrl` (`SANCHORIS_CONTROL_URL`) follow the custom domains when they exist and the `execute-api` endpoints otherwise. Set the GitHub App's callback URL to `<public URL>/auth/github/callback`, its Setup URL to `<public URL>/auth/github/setup`, its webhook URL to `<public URL>/webhooks/github`, and the Stripe webhook endpoint to `<public URL>/webhooks/stripe`.

HTTP APIs take neither resource policies nor AWS WAF, so the Cloudflare-only IP restriction of [013](013-sanrecap.md) does not carry over: the regional endpoint of the custom domain answers requests that skip Cloudflare. Mutual TLS on the custom domain with the Cloudflare origin pull CA (Authenticated Origin Pulls) restricts it to Cloudflare.

## Cost estimate

Monthly USD in ap-northeast-1, before tax. Unit prices come from the AWS Price List API for ap-northeast-1 (`aws pricing get-products`), except EventBridge Scheduler, which is from its pricing page. Free tiers are the always-free amounts on the pricing pages.

| Item | Unit price | Dev idle | Light use |
|------|------------|----------|-----------|
| KMS key | $1 per key-month; $0.03 per 10,000 requests | 1.00 | 1.10 (about 50,000 requests) |
| EventBridge Scheduler | 14 million invocations free, then $1.00 per million | 0 (about 98,000 sweep invocations) | 0 |
| SQS FIFO | $0.50 per million requests (1 million free) | 0 to 0.50 (Lambda polling of an idle queue and the sweeps) | 0.50 |
| Lambda (ARM) | $0.20 per million requests, $0.0000133334 per GB-s (1 million requests and 400,000 GB-s free) | 0 (sweeps, about 10,000 GB-s) | 0 |
| DynamoDB on-demand | $0.715 per million WRU, $0.1425 per million RRU, $0.285 per GB-month after 25 GB, PITR $0.228 per GB-month | 0.05 | 1.00 (1 million WRU, 2 million RRU) |
| API Gateway HTTP API | $1.29 per million requests | 0 | 0.07 (50,000 requests) |
| API Gateway WebSocket | $1.26 per million messages, $0.315 per million connection minutes | 0 | 0.25 (200,000 messages) |
| S3 Standard | $0.025 per GB-month, $0.0047 per 1,000 PUT | 0 | 0.15 (5 GB) |
| CloudWatch Logs | $0.76 per GB ingested, $0.033 per GB-month (5 GB free) | 0 | 0 |
| ECR | $0.10 per GB-month | 0.10 (one 1 GB image) | 0.50 (5 images) |
| Fargate (ARM) | $0.04045 per vCPU-hour, $0.00442 per GB-hour | 0 | 0.99 (20 Workspaces x 30 min at 2 vCPU / 4 GiB) |
| Interface endpoints (optional) | $0.014 per endpoint-hour, $0.01 per GB | 0 (off) | 30.66 (3 endpoints) |
| Network relay (next step) | Fargate 0.25 vCPU / 0.5 GB + public IPv4 $0.005/h | 0 (not deployed) | 12.65 |
| **Total** | | **about 1.2 to 1.7** | **about 48** |

Light use means about 20 Tasks a month with one 30 minute Workspace each and a few people on the screens. The fixed cost of running Workspaces at all is the endpoints and the relay (about $43); the per-Task cost is about $0.05.

Pricing pages: [Lambda](https://aws.amazon.com/lambda/pricing/), [API Gateway](https://aws.amazon.com/api-gateway/pricing/), [DynamoDB on-demand](https://aws.amazon.com/dynamodb/pricing/on-demand/), [S3](https://aws.amazon.com/s3/pricing/), [KMS](https://aws.amazon.com/kms/pricing/), [SQS](https://aws.amazon.com/sqs/pricing/), [EventBridge (Scheduler)](https://aws.amazon.com/eventbridge/pricing/), [Fargate](https://aws.amazon.com/fargate/pricing/), [PrivateLink (VPC endpoints)](https://aws.amazon.com/privatelink/pricing/), [VPC (NAT gateway, public IPv4)](https://aws.amazon.com/vpc/pricing/), [CloudWatch](https://aws.amazon.com/cloudwatch/pricing/), [ECR](https://aws.amazon.com/ecr/pricing/).
