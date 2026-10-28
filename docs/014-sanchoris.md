# 014: sanchoris

The `sanchoris` module builds the AWS side of [sanchoris](https://github.com/conao3/sanchoris): a serverless control plane (API Gateway, Lambda, DynamoDB, S3, SQS, EventBridge Scheduler) and ECS Fargate for Workspaces. It does not run on k8s and is not part of `deploy all`. The contract with the application is sanchoris `docs/architecture.md`; the DynamoDB tables come from sanchoris `infra/tables.json`, so the table definitions are never retyped here.

## Resources

| Group | Resources |
|-------|-----------|
| Tables | One `AWS::DynamoDB::Table` per entry of `infra/tables.json` (`properties` as is, name `<prefix>-sanchoris-<role>`). Today: `main` (stream `NEW_IMAGE`, TTL `expires_at`, two GSIs) and `audit` (append-only). Both on-demand, PITR, deletion protection |
| Objects | Two S3 buckets with SSE-S3 (`AES256`) as the default encryption, public access blocked, TLS only, incomplete multipart uploads aborted after 1 day: `docs` (offloaded attributes, kept) and `records` (session records and audit exports, expire after 90 days, the longest `retentionDays` in `infra/plans.json`). The app sends no encryption headers. Run log lines live in DynamoDB |
| Work queue | SQS FIFO `<prefix>-sanchoris-work.fifo` (content-based deduplication, visibility timeout 1800 s = 6 x the worker timeout, `maxReceiveCount` 5) and DLQ `-work-dlq.fifo`; standard queue `-relay-dlq` for stream records the relay gave up on |
| Timers | EventBridge Scheduler group `<prefix>-sanchoris`, the role that lets schedules send to the work queue, and the five rate schedules of `docs/architecture.md` ("Rate schedules"). The relay creates one-time schedules in the same group |
| HTTP API | API Gateway HTTP API (`$default` stage, 20 requests/s, burst 40, JSON access logs). Routes in `http-routes`: `ANY /api/{proxy+}` with the Cognito JWT authorizer (issuer = the shared pool, audience = the sanchoris app client) and `$default` without an authorizer for `/webhooks/*`, `/auth/github*`, `/health`, and the web app, which the api function serves from the frontend build embedded in its binary (the api checks webhook signatures, the OAuth `state`, and the bearer token itself) |
| Run channel | API Gateway WebSocket API (stage `prod`, `$connect` / `$disconnect` / `$default` to the channel Lambda, JSON access logs). `$connect` has a REQUEST authorizer (the channel-authorizer Lambda, identity source `route.request.header.Authorization`) that verifies the Run token |
| Compute | Five Lambda functions (`functions`, below), event source mappings for the work queue and for every table listed in `streamFilters` |
| Workspaces | VPC `10.1.0.0/16` with a public subnet `10.1.0.0/24` (the relay, and the Workspaces in the public network), ECR repository `<prefix>-sanchoris-workspace` (scan on push, last 10 images), ECS cluster `<prefix>-sanchoris` (Fargate and Fargate Spot, Container Insights off), two Workspace task definitions, `<prefix>-sanchoris-workspace-arm64` (ARM64, image tag `<WorkspaceImageTag>-arm64`) and `<prefix>-sanchoris-workspace-x86_64` (X86_64, `<WorkspaceImageTag>-amd64`), both 1 vCPU / 4 GiB at the task level, which the worker replaces with the tenant's Plan size through `RunTask` overrides (`infra/plans.json`: Free and Starter 1 vCPU / 4 GiB, Pro and Max 2 vCPU / 4 GiB), 30 GiB ephemeral storage, user `1000:1000`, all Linux capabilities dropped, `stopTimeout` 120 s, no log driver, no ECS init process: the image runs `tini` itself so that no process keeps the environment with the Run and relay tokens; the root file system stays writable because git, Claude Code, and package managers write outside `/workspace`, `/home/node`, and `/tmp`), its execution role (image pull only), and security groups for Workspaces and the relay. The strict network adds the strict subnet `10.1.16.0/20` without a route to the internet, the S3 gateway endpoint, an endpoint security group, and (with Workspaces enabled) the `ecr.api` and `ecr.dkr` interface endpoints ("Workspace network") |
| Egress relay | ECR repository `<prefix>-sanchoris-relay` (last 5 images), its task definition (ARM64, 0.25 vCPU / 0.5 GB, user `1000:1000`, read-only root file system, all Linux capabilities dropped, `nofile` 65536), execution role (image pull, logs, the relay signing key from SSM) and log group `/aws/ecs/<prefix>-sanchoris-relay`, and the ECS service `<prefix>-sanchoris-relay` in the public subnet with a public IP, created with desired count 0 when Workspaces are enabled and deployed with `MinimumHealthyPercent` 100 and `MaximumPercent` 200. The worker scales it to 1 while Workspaces run ("Egress relay") |
| Logs | One log group per Lambda, the relay log group, and the two access log groups, all with 30 days retention. Workspaces write no CloudWatch logs: the agent's log lines reach DynamoDB (`LOG#`) over the Run channel |

## Lambda functions

The function list is data (`functions` in `src/conao3/aws_infra_k8s/sanchoris.clj`): each entry has a name, timeout, memory, the environment groups (`env-vars`), the secrets it reads from SSM (`secrets`), and the permission groups (`grant-statements`). The triggers name their function by id (`http-function`, `channel-function`, `channel-authorizer-function`, `work-function`, `stream-function`). Code is `target/lambda/<name>/bootstrap.zip` in sanchoris, runtime `provided.al2023`, handler `bootstrap`, `arm64`, JSON logs. The table matches sanchoris `docs/architecture.md` ("Lambda functions").

| Function | Trigger | Timeout / memory | Environment | Secrets (SSM) | Permissions |
|----------|---------|------------------|-------------|---------------|-------------|
| `api` | HTTP API (payload 2.0): `ANY /api/{proxy+}` (JWT) and `$default` | 29 s / 512 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_ALLOWED_SOURCES`, `SANCHORIS_PUBLIC_URL`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `GITHUB_URL`, `GITHUB_API_URL`, `STRIPE_API_URL`, `STRIPE_PORTAL_CONFIGURATION`, `SANCHORIS_RECORDS_STORE=s3`, `SANCHORIS_RECORDS_BUCKET` | `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_CLIENT_SECRET`, `GITHUB_APP_SLUG`, `GITHUB_WEBHOOK_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `TASK_TOKEN_SIGNING_KEY`, `OAUTH_STATE_SIGNING_KEY`, `SANCHORIS_CREDENTIAL_KEY` | tables, docs, records, `ssm:GetParameters` on its parameters |
| `channel-authorizer` | REQUEST authorizer of the WebSocket API's `$connect` | 10 s / 256 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_ALLOWED_SOURCES` | `RUN_TOKEN_SIGNING_KEY` | `dynamodb:GetItem` on `main`, docs read, `ssm:GetParameters` on its parameter |
| `channel` | WebSocket API `$connect`, `$default`, `$disconnect` | 29 s / 256 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_RECORDS_STORE=s3`, `SANCHORIS_RECORDS_BUCKET`, `SANCHORIS_CHANNEL_ENDPOINT`, `GITHUB_API_URL` | `RUN_TOKEN_SIGNING_KEY`, `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `SANCHORIS_CREDENTIAL_KEY` | tables, docs, records, `execute-api:ManageConnections` (POST and DELETE on `@connections` of the stage), `ssm:GetParameters` on its parameters |
| `worker` | Work queue (batch 10, `ReportBatchItemFailures`, `MaximumConcurrency` = `WorkerMaxConcurrency`, default 5) | 300 s / 512 MB | `SANCHORIS_TABLE_MAIN`, `SANCHORIS_TABLE_AUDIT`, `SANCHORIS_DOCS_BUCKET`, `SANCHORIS_SECRETS_PATH`, `SANCHORIS_RECORDS_STORE=s3`, `SANCHORIS_RECORDS_BUCKET`, `SANCHORIS_CHANNEL_ENDPOINT`, `GITHUB_API_URL`, `SANCHORIS_ECS_CLUSTER`, `SANCHORIS_ECS_TASK_DEFINITION_ARM64`, `SANCHORIS_ECS_TASK_DEFINITION_X86_64`, `SANCHORIS_ECS_CONTAINER`, `SANCHORIS_ECS_SUBNETS` (the public or the strict subnet), `SANCHORIS_ECS_SECURITY_GROUPS`, `SANCHORIS_ECS_ASSIGN_PUBLIC_IP` (`ENABLED` in the public network, `DISABLED` in the strict one), `SANCHORIS_CONTROL_URL`, `SANCHORIS_GIT_URL`, `SANCHORIS_RELAY_SERVICE` (`<prefix>-sanchoris-relay`), `SANCHORIS_RELAY_PORT` (3128) | `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `RUN_TOKEN_SIGNING_KEY`, `RELAY_TOKEN_SIGNING_KEY` | tables, docs, records, `ManageConnections`, receive/delete/change visibility on the work queue, `ecs:RunTask` of the two Workspace families on this cluster, stop/describe/tag its tasks, `ecs:ListTasks` in the cluster, `ecs:UpdateService` / `ecs:DescribeServices` on the relay service, pass the execution role, `ssm:GetParameters` on its parameters |
| `relay` | DynamoDB Streams of `main` (filter from `streamFilters`, batch 100, bisect on error, 10 retries, failures to `relay-dlq`) | 60 s / 256 MB | `SANCHORIS_WORK_QUEUE_URL`, `SANCHORIS_WORK_QUEUE_ARN`, `SANCHORIS_SCHEDULE_GROUP`, `SANCHORIS_SCHEDULER_ROLE_ARN` | — | stream read, send to the work queue and `relay-dlq`, create/delete/get schedules in the group, pass the scheduler role |

`SANCHORIS_SECRETS_PATH` is `/<prefix>/sanchoris/`, `SANCHORIS_ALLOWED_SOURCES` is `cloudflare` when the stack has custom domains and `any` otherwise, and `SANCHORIS_CHANNEL_ENDPOINT` the WebSocket stage's `https://<api id>.execute-api.<region>.amazonaws.com/prod` (output `SanchorisChannelEndpoint`). Table permissions follow `infra/tables.json`: tables with `appendOnly` get `PutItem`, `GetItem`, `Query`, `ConditionCheckItem` and an explicit Deny of `appendOnlyDeniedActions`; the other tables get item reads and writes with an explicit Deny of writes whose leading key is in `readOnlyLeadingKeys` (`PLANS`, written at deploy time by an administrator). Every bucket permission comes with `s3:ListBucket` (a missing key reads as 404). Tenant separation in the records bucket is the `t/<tenant>/` key prefix, which the app checks before it presigns or writes a key.

## Workspace network

The stack parameter `WorkspaceNetwork` (`SANCHORIS_WORKSPACE_NETWORK`, required at deploy, `public` or `strict`) chooses where Workspaces run. Both send Workspace traffic through the egress relay, and the task definition has no task role, so the agent gets no AWS credentials.

| Network | Subnet and image pull | Workspace security group egress | Fixed cost (ap-northeast-1, per month) | What the relay enforces |
|---------|-----------------------|----------------------------------|----------------------------------------|-------------------------|
| `public` (dev) | Public subnet, a public IP per task; ECR over the internet | 3128 to the relay, 443 to `0.0.0.0/0` | 0 (public IPv4 $0.005/h while a task runs) | Advisory: Claude Code, git, and Node clients (`NODE_USE_ENV_PROXY`) go through the relay, but a process that ignores `HTTPS_PROXY` reaches any host on port 443 directly |
| `strict` | Strict subnet without an internet route; ECR through `ecr.api` and `ecr.dkr` interface endpoints in one AZ, layers through the S3 gateway endpoint (only `GetObject` on the ECR layer bucket) | 3128 to the relay, 443 to the endpoint security group and to the S3 prefix list | 2 x $0.014/h = $20.44, plus $0.01/GB | Everything: the relay is the only way out |

In the public network, the relay's deny list, platform hosts, and `Network.Allow` are a guard rail for well-behaved tools, not a boundary against a misbehaving agent. Use `strict` for an environment where the agent must not reach hosts outside `Network.Allow`. A NAT gateway ($0.062/h = $45.26 per month, plus $0.062/GB) would also need the relay or AWS Network Firewall on top, so neither layout uses one.

`EnableWorkspaces` (`SANCHORIS_ENABLE_WORKSPACES=true`) turns the Workspace feature on per environment: it creates the relay service, and in the strict network the interface endpoints. Without it the control plane runs, and starting a Workspace fails with "the egress relay service ... is not active" before `RunTask`. In the strict network the endpoints' private DNS covers the whole VPC, so the relay also pulls its image through them (the endpoint security group admits the relay security group); in the public network it pulls over the internet. The strict subnet is in one AZ (the first of `Fn::GetAZs`) so that one set of endpoints serves it.

### Egress relay

The relay (sanchoris `apps/egress-relay`, image `infra/relay/Dockerfile`, `linux/arm64`) is an HTTP `CONNECT` proxy on port 3128 in the public subnet; the relay security group admits port 3128 from Workspaces and allows 443 to the internet. On each `CONNECT` the relay checks the relay token, then allows port 443 only, applies the operator deny list (`relay-deny-hosts`), then the platform hosts (`relay-platform-hosts`: the Anthropic API and login hosts, `github.com`, `api.github.com`, `*.githubusercontent.com`, the Run channel host, and the records bucket), then the Run's allow list, and refuses private, link-local, and reserved addresses. It keeps at most 256 open tunnels per run and 8192 connections in all, closes sockets idle for 10 minutes, and closes every tunnel on `SIGTERM`; the task's `nofile` limit (65536) leaves room for both sides of every connection. It logs one JSON line per `CONNECT`.

The relay service runs only while Workspaces run. The worker keeps a lease item (`pk = RELAY`, `sk = #` in `main`) with a lock and the runs that use the relay; before `RunTask` it sets the service's desired count to 1 under the lock, waits for the relay task to be `RUNNING` (up to 240 s, and no later than 30 s before the worker invocation ends), and passes `http://run:<relay token>@<relay task private IP>:3128` to the Workspace as `SANCHORIS_RELAY_URL`. The Workspace's entrypoint moves it and the Run token from the environment to the agent's stdin; the agent's loopback forwarder (`HTTPS_PROXY=http://127.0.0.1:3129`) adds the relay token to each `CONNECT`, so no process in the Workspace sees it. When a Workspace stops (`stop_workspace`), and every minute in `sweep_workspaces` while the desired count is above 0, the worker drops runs without a running Workspace task and sets the desired count to 0 when none is left. The relay token carries the Run's `Network.Allow` and its HMAC under `RELAY_TOKEN_SIGNING_KEY`, which the relay task receives from SSM through its task definition. A relay task that ECS replaces gets a new IP, and Workspaces that already run keep the old address. A deployment of the relay service starts the new task before it stops the old one (`MinimumHealthyPercent` 100), so Workspaces keep their way out until the old task stops and lose it then; deploy a new relay image or task definition when no Workspace runs (`aws ecs list-tasks --cluster <prefix>-sanchoris --family <prefix>-sanchoris-workspace-arm64` and the same for `-x86_64` are empty).

The relay does not intercept TLS. Git in the Workspace gets GitHub installation tokens narrowed to the Session's `GitHub.Repos` and `GitHub.Permissions` from the control plane over the Run channel (sanchoris `docs/architecture.md` "Workspaces"). DNS queries from the Workspace go to the VPC resolver; Route 53 Resolver DNS Firewall ($0.60 per million queries) closes DNS as a side channel.

A Workspace that stops before its agent connects to the Run channel (image pull failure, a crash at start) leaves no CloudWatch log. The worker's `sweep_workspaces` reads the stopped task with `DescribeTasks` and writes its `stoppedReason`, stop code, and the container's exit code to the run (`error`) and to the task (`NEEDS_YOU`, `Workspace.Stopped`, `cause`).

### Workspace capacity and architecture

The worker starts every Workspace with the capacity provider `FARGATE_SPOT` and, when the `RunTask` response says Spot has no capacity, once more with `FARGATE` (On-Demand); the Workspace item records which one started it (sanchoris `docs/architecture.md`, "Workspaces"). The Session's `Workspace.Architecture` (`arm64` or `x86_64`) picks the task definition. Fargate sends `SIGTERM` two minutes before it interrupts a Spot task, and the task definitions' `stopTimeout` (120 s) gives the agent that time to save its session record; the worker then restores the session in a new Workspace.

Push the images before the first deploy with `SANCHORIS_ENABLE_WORKSPACES=true`. The Workspace image runs every `RUN` step on the build platform and only copies the target's files into the target's base image, and the relay image only copies files, so an x86_64 host builds both architectures without emulation:

```bash
R=<account>.dkr.ecr.ap-northeast-1.amazonaws.com
aws ecr get-login-password | docker login --username AWS --password-stdin $R
docker build --platform linux/arm64 -f infra/workspace/Dockerfile -t $R/dev-k8s-sanchoris-workspace:latest-arm64 .   # in sanchoris
docker build --platform linux/amd64 -f infra/workspace/Dockerfile -t $R/dev-k8s-sanchoris-workspace:latest-amd64 .   # in sanchoris
docker build --platform linux/arm64 -f infra/relay/Dockerfile -t $R/dev-k8s-sanchoris-relay:latest apps/egress-relay
docker push $R/dev-k8s-sanchoris-workspace:latest-arm64
docker push $R/dev-k8s-sanchoris-workspace:latest-amd64
docker push $R/dev-k8s-sanchoris-relay:latest
```

`RunTask` resolves the Workspace tag at each start, and the relay service resolves the relay tag each time it starts a task, so a new image takes effect at the next Task.

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
   | `relay-token-signing-key` | `RELAY_TOKEN_SIGNING_KEY` (32+ bytes; the worker and the relay task) |

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
5. The ECS service-linked role `AWSServiceRoleForECS` exists in the account. `CreateCluster` fails with "Unable to assume the service linked role" when the stack creates it in an account that has never used ECS; create it first with `aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com` (dev and prd have it).

## Deploy

```bash
export AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1
export SANCHORIS_ENABLE_WORKSPACES=true SANCHORIS_WORKSPACE_NETWORK=public SANCHORIS_WORKER_MAX_CONCURRENCY=2
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy cognito
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanchoris-certificate
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanchoris
```

prd uses the same commands with `DEPLOY_ENV=prd AWS_PROFILE=conao3.prd.k8s` ("Environments").

`deploy sanchoris` runs `sam validate --lint` first and passes these parameters:

| Parameter | Source |
|-----------|--------|
| `ApiDomainName`, `ChannelDomainName`, `CertificateArn` | `<env>-sanchoris.sancode.dev`, `<env>-sanchoris-channel.sancode.dev` (prd without the env) and export `<prefix>-SanchorisCertificateArn`, or `SANCHORIS_CERTIFICATE_ARN`. Without the certificate no custom domain is created |
| `S3PrefixListId` | `aws ec2 describe-prefix-lists` (`com.amazonaws.ap-northeast-1.s3`) |
| `EnableWorkspaces` | `SANCHORIS_ENABLE_WORKSPACES=true`; default `false` |
| `WorkspaceNetwork` | `SANCHORIS_WORKSPACE_NETWORK` (`public` or `strict`); required |
| `WorkspaceImageTag` | `SANCHORIS_WORKSPACE_IMAGE_TAG`; default `latest`. The task definitions use `<tag>-arm64` and `<tag>-amd64` |
| `RelayImageTag` | `SANCHORIS_RELAY_IMAGE_TAG`; default `latest` |
| `WorkerMaxConcurrency` | `SANCHORIS_WORKER_MAX_CONCURRENCY`; default 5 |

The template's parameters carry no `Description`; this table and the Resources table describe them. The minified template stays under the 51,200 bytes that `aws cloudformation validate-template --template-body` accepts (about 49,100 today) because the Lambda settings shared by every function sit in SAM `Globals` and each function role lists its SSM parameters with one `Fn::Sub` and `Fn::Split`. Check both networks before a change is merged:

```bash
jq -c '.Parameters.WorkspaceNetwork.Default="public"' target/cfn/sanchoris.json > target/cfn/sanchoris-public.json
jq -c '.Parameters.WorkspaceNetwork.Default="strict"' target/cfn/sanchoris.json > target/cfn/sanchoris-strict.json
for f in public strict; do
  wc -c target/cfn/sanchoris-$f.json
  nix develop --command sam validate --lint --template-file target/cfn/sanchoris-$f.json --region ap-northeast-1
  aws cloudformation validate-template --template-body file://target/cfn/sanchoris-$f.json
done
```

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

The dev and prd accounts each allow 10 concurrent Lambda executions in total, and the sanrecap worker may take 5 of them; with `WorkerMaxConcurrency` 5 the sanchoris worker could take the rest and starve the api and channel functions. Use `SANCHORIS_WORKER_MAX_CONCURRENCY=2` in both until the quota is raised. Fargate allows 30 vCPU for On-Demand and 30 for Spot in both accounts, which is 30 Free or Starter Workspaces (1 vCPU) or 15 Pro or Max Workspaces (2 vCPU) at a time.

## Environments

| | dev | prd |
|---|---|---|
| `DEPLOY_ENV`, prefix, AWS profile | `dev`, `dev-k8s`, `conao3.dev.k8s` | `prd`, `prd-k8s`, `conao3.prd.k8s` |
| Web app, webhooks, OAuth (HTTP API) | `https://dev-sanchoris.sancode.dev` | `https://sanchoris.sancode.dev` |
| Run channel (WebSocket API) | `wss://dev-sanchoris-channel.sancode.dev` | `wss://sanchoris-channel.sancode.dev` |
| Cognito Hosted UI (`VITE_COGNITO_DOMAIN`) | `dev-auth-k8s.sancode.dev` | `auth-k8s.sancode.dev` |
| Cognito callbacks of the sanchoris client | `https://sanchoris.localhost/auth/callback`, `https://dev-sanchoris.sancode.dev/auth/callback` | `https://sanchoris.sancode.dev/auth/callback` |
| SSM | `/dev-k8s/sanchoris/` | `/prd-k8s/sanchoris/` |
| GitHub App | "Sanchoris (dev)" (`sanchoris-dev`) | "Sanchoris App" (`sanchoris-app`) |
| Stripe | Test mode | Live mode |
| Stack settings | `SANCHORIS_ENABLE_WORKSPACES=true`, `SANCHORIS_WORKSPACE_NETWORK=public`, `SANCHORIS_WORKER_MAX_CONCURRENCY=2` | Same |

Both environments mirror each other: the HTTP API serves the web app, `/api`, the webhooks, and the GitHub OAuth on one host name, and the Run channel has its own, both one level under `sancode.dev` so that Cloudflare's Universal SSL covers the proxied names. The api zip embeds the frontend built with the environment's Cognito values, so build the functions for the environment you deploy (`VITE_COGNITO_DOMAIN=<Hosted UI> VITE_COGNITO_CLIENT_ID=<SanchorisUserPoolClient of that environment> nix develop -c pnpm build:lambda`) and push the images to that account's repositories.

The prd `cognito` stack also holds the sanplan and sanrecap clients. Before updating it, create a change set from the generated template (`aws cloudformation create-change-set --template-body file://target/cfn/cognito.json` with `UsePreviousValue` for every parameter and `CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND`), read it with `describe-change-set --include-property-values`, and execute it only when it changes nothing but the sanchoris client.

Steps for a new environment, in order:

1. `deploy cognito` (the sanchoris client) and `deploy sanchoris-certificate`; add the certificate's two DNS validation CNAMEs (DNS only).
2. Create the GitHub App under `sancode-org` with the manifest flow (public; permissions: Metadata read, Contents, Issues, and Pull requests read and write, Checks and Actions read; events: `issues`, `issue_comment`, `issue_dependencies`, `pull_request`, `push`, `check_run`, `repository`; webhook `<public URL>/webhooks/github`, callback `<public URL>/auth/github/callback`, Setup URL `<public URL>/auth/github/setup`), and put its id, slug, private key, client id, client secret, and webhook secret into SSM.
3. In Stripe, create the Starter, Pro, and Max products and monthly USD prices (lookup keys `sanchoris_<plan>`), a customer portal configuration that switches between the three prices without proration and cancels at the period end, a webhook endpoint `<public URL>/webhooks/stripe` with `checkout.session.completed`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`, `checkout.session.expired`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, and `invoice.payment_failed`, and the app's restricted key (Customers, Products, Prices read; Subscriptions, Checkout Sessions, Customer Portal write). Put the key, the webhook secret, the prices, and the portal configuration into SSM. A temporary key with write access to Products, Prices, Customer Portal, and Webhook Endpoints creates the objects through the API; expire it afterwards.
4. Generate the five signing keys (`run-token-signing-key`, `task-token-signing-key`, `oauth-state-signing-key`, `relay-token-signing-key`: 32 random bytes in hex; `credential-key`: 32 random bytes in base64).
5. Build the functions for the environment, `deploy sanchoris`, push the Workspace and relay images, add the two proxied CNAMEs, and seed `PLANS`.

## Custom domains behind Cloudflare

`deploy sanchoris-certificate` creates one ACM certificate in ap-northeast-1 for both host names (`DomainName` plus one subject alternative name), because an API Gateway custom domain maps either HTTP/REST APIs or WebSocket APIs, so the Run channel has its own host name. Add both DNS validation records to the `sancode.dev` zone (DNS only), as in [013](013-sanrecap.md).

After `deploy sanchoris`, add two **proxied** CNAMEs: `<env>-sanchoris` to `SanchorisApiDomainTarget` and `<env>-sanchoris-channel` to `SanchorisChannelDomainTarget`. With the custom domains the `execute-api` endpoints of the HTTP API and of the WebSocket API are disabled, and `SANCHORIS_CHANNEL_ENDPOINT` (output `SanchorisChannelEndpoint`) becomes `https://<channel domain>`, so the channel and worker functions call the API Gateway Management API (`@connections`) through the custom domain and Cloudflare; the default endpoint answers 403 to `@connections` once it is disabled. If the WebSocket API's default endpoint still answers after the first deploy with the setting, redeploy the stage (`aws apigatewayv2 create-deployment --api-id <ChannelApi> --stage-name prod`). The outputs `SanchorisPublicUrl` (`SANCHORIS_PUBLIC_URL`) and `SanchorisControlUrl` (`SANCHORIS_CONTROL_URL`) follow the custom domains when they exist and the `execute-api` endpoints otherwise. Set the GitHub App's callback URL to `<public URL>/auth/github/callback`, its Setup URL to `<public URL>/auth/github/setup`, its webhook URL to `<public URL>/webhooks/github`, and the Stripe webhook endpoint to `<public URL>/webhooks/stripe`.

HTTP APIs take neither resource policies nor AWS WAF, so the application restricts the source address instead of the Cloudflare-only resource policy of [013](013-sanrecap.md): with custom domains `SANCHORIS_ALLOWED_SOURCES` is `cloudflare`, the api function answers 403 to a request whose source address is outside Cloudflare's published ranges, and the channel authorizer denies a `$connect` from such an address (sanchoris `docs/architecture.md`, "Client IP"). A request to the regional endpoint of a custom domain that skips Cloudflare therefore gets 403.

## Cost estimate

Monthly USD in ap-northeast-1, before tax, for the public Workspace network with Workspaces enabled. Unit prices come from the AWS Price List API for ap-northeast-1 (`aws pricing get-products`), except EventBridge Scheduler, which is from its pricing page. Free tiers are the always-free amounts on the pricing pages. SSE-S3 has no charge.

| Item | Unit price | Dev idle | Light use | Per Task |
|------|------------|----------|-----------|----------|
| EventBridge Scheduler | 14 million invocations free, then $1.00 per million | 0 (about 142,000 sweep invocations) | 0 | 0 |
| SQS FIFO | $0.50 per million requests (1 million free) | 0 to 0.50 (Lambda polling of an idle queue and the sweeps) | 0.50 | 0 |
| Lambda (ARM) | $0.20 per million requests, $0.0000133334 per GB-s (1 million requests and 400,000 GB-s free) | 0 (sweeps, about 15,000 GB-s) | 0 | 0 |
| DynamoDB on-demand | $0.715 per million WRU, $0.1425 per million RRU, $0.285 per GB-month after 25 GB, PITR $0.228 per GB-month | 0.05 | 1.00 (1 million WRU, 2 million RRU) | 0 |
| API Gateway HTTP API | $1.29 per million requests | 0 | 0.07 (50,000 requests) | 0 |
| API Gateway WebSocket | $1.26 per million messages, $0.315 per million connection minutes | 0 | 0.25 (200,000 messages) | 0 |
| S3 Standard | $0.025 per GB-month, $0.0047 per 1,000 PUT | 0 | 0.15 (5 GB) | 0 |
| CloudWatch Logs | $0.76 per GB ingested, $0.033 per GB-month (5 GB free) | 0 | 0 | 0 |
| ECR | $0.10 per GB-month | 0.10 to 0.30 (Workspace images of 0.68 GB, relay image of 0.06 GB) | 0.30 | 0 |
| Fargate Spot ARM (Workspace, 1 vCPU / 4 GiB, Starter) | $0.01249013 per vCPU-hour, $0.0013648 per GB-hour ($0.01795 per hour) | 0 | 0.18 | 0.009 (30 min) |
| Public IPv4 of a Workspace | $0.005 per hour | 0 | 0.05 | 0.0025 |
| Egress relay (Fargate ARM 0.25 vCPU / 0.5 GB and its public IPv4) | $0.04045 per vCPU-hour, $0.00442 per GB-hour, $0.005 per IPv4-hour ($0.0173 per hour) | 0 (desired count 0) | 0.18 | 0.009 (about 32 min: the Workspace plus the start and the sweep) |
| **Total** | | **about 0.2 to 0.9** | **about 2.7** | **about 0.02** |

Workspace prices per hour in ap-northeast-1 (the dev estimate above uses Starter's Spot ARM size; replace that row with the one that applies):

| Capacity and architecture | Unit price | 1 vCPU / 4 GiB (Free, Starter) | 2 vCPU / 4 GiB (Pro, Max) |
|---------------------------|------------|--------------------------------|---------------------------|
| Fargate Spot ARM (the default) | $0.01249013 per vCPU-hour, $0.0013648 per GB-hour | $0.01795 | $0.03044 |
| Fargate Spot x86 (`Architecture: x86_64`) | $0.01561189 per vCPU-hour, $0.00170755 per GB-hour | $0.02244 | $0.03805 |
| Fargate ARM On-Demand (Spot had no capacity) | $0.04045 per vCPU-hour, $0.00442 per GB-hour | $0.05813 | $0.09858 |
| Fargate x86 On-Demand | $0.05056 per vCPU-hour, $0.00553 per GB-hour | $0.07268 | $0.12324 |

The 30 GiB of ephemeral storage add $0.00133 per hour (10 GiB over the included 20 GiB at $0.000133 per GB-hour). The On-Demand prices are from the Price List API. The Price List API has no Fargate Spot SKU, so the Spot prices are from `https://dftu77xade0tc.cloudfront.net/fargate-spot-prices.json`, the file the [Fargate pricing page](https://aws.amazon.com/fargate/pricing/) reads, which AWS does not guarantee.

Light use means about 20 Tasks a month with one 30 minute Workspace each and a few people on the screens; Tasks that overlap share one relay task. Traffic out to the internet (the Anthropic API, GitHub) is within the 100 GB a month that AWS does not charge. The strict network adds the two interface endpoints: $20.44 a month fixed, plus $0.01/GB of image pulls.

prd has the same layout as dev, so the same figures apply: about $0.2 to $0.9 for an idle month (the ECR images, 1.36 GB of Workspace images and the relay image, and the SQS polling) and about $0.02 for a Task with a 30 minute Workspace on Free or Starter (Spot ARM at 1 vCPU: $0.009, the Workspace's public IPv4 $0.0025, the relay $0.009), about $0.027 on Pro or Max (2 vCPU: $0.015 for the Workspace). A Task that falls back to On-Demand costs about $0.04 (1 vCPU) or $0.06 (2 vCPU). The model usage is on the tenant's own Claude or Codex credential and does not reach the AWS bill.

Pricing pages: [Lambda](https://aws.amazon.com/lambda/pricing/), [API Gateway](https://aws.amazon.com/api-gateway/pricing/), [DynamoDB on-demand](https://aws.amazon.com/dynamodb/pricing/on-demand/), [S3](https://aws.amazon.com/s3/pricing/), [SQS](https://aws.amazon.com/sqs/pricing/), [EventBridge (Scheduler)](https://aws.amazon.com/eventbridge/pricing/), [Fargate](https://aws.amazon.com/fargate/pricing/), [PrivateLink (VPC endpoints)](https://aws.amazon.com/privatelink/pricing/), [VPC (NAT gateway, public IPv4)](https://aws.amazon.com/vpc/pricing/), [CloudWatch](https://aws.amazon.com/cloudwatch/pricing/), [ECR](https://aws.amazon.com/ecr/pricing/).
