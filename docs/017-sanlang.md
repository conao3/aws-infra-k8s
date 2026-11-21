# Sanlang optional server inference

**Local infrastructure definitions only. No resources have been deployed. All
AWS commands, image pushes, DNS changes and deployments below require Papa's
explicit approval; none were run for this change.** Device inference remains the
web default. No server option is enabled in the Chrome extension.

## Architecture

```text
Web (Device by default; optional Server, Cognito ID token)
  → Cloudflare proxied <env>-sanlang-api.sancode.dev
  → regional API Gateway REST API, root base-path mapping → stage api
      POST /v1/translate → arm64 container Lambda translate → local llama-server
      GET /v1/config, GET /v1/me, OPTIONS on all three routes → arm64 zip Lambda api
  → DynamoDB usage: userId (S) + period (S, YYYY-MM UTC)
```

This is not a Kubernetes service. `sanlang`, `sanlang-repository` and
`sanlang-certificate` are opt-in CLI targets, not part of `deploy all`. The shared
`cognito` stack adds one public Sanlang app client and exports its ID. Existing
user pools, clients and login domain remain unchanged. Review the shared-stack
change set, especially in prd; do not replace the pool or other applications.
Billing/Stripe, subscriptions and payment-driven quotas are future work.

The repository stack calls the existing `ecr.clj` helper: repository
`<prefix>-sanlang-translate` (for example `dev-k8s-sanlang-translate`), scan on push,
keep the last 10 images. Sanlang additionally makes tags immutable, retains the
repository on stack deletion and grants image retrieval to this account's
translate Lambda only. Keep the deployed image available: a lifecycle expiration
of an old deployed image can prevent Lambda reactivation. Use unique release tags
and retain/redeploy the active version before it ages out of the last 10.

Translate is 10,240 MB, arm64, 300 s, using the production target of Sanlang's
`apps/server/Dockerfile`. There is no auth bypass or measurement handler. Lambda
processes one invocation per execution environment; llama-server has one slot.
No global reserved concurrency of 1 or provisioned concurrency is imposed. The
zip API is Node.js 26, arm64, 256 MB, 29 s (`api.mjs`, handler `api.handler`).
Both log groups retain 30 days. No request/response body tracing is enabled.
The table is on-demand, has PITR and deletion protection, and is retained on
deletion/replacement. IAM allows translate **UpdateItem only**, api **GetItem
only**, on this exact table; each can write only its own pre-created log group.
No VPC, NAT, SSM secrets, billing resources or public Lambda Function URLs are
needed. Lambda supplies the reserved `AWS_REGION` variable automatically.

## REST streaming contract

The exact CloudFormation settings are:

- `AWS::ApiGateway::RestApi.EndpointConfiguration.Types: [REGIONAL]`.
- `AWS::ApiGateway::Method.Integration.Type: AWS_PROXY`,
  `IntegrationHttpMethod: POST`, `ResponseTransferMode: STREAM`,
  `TimeoutInMillis: 300000` on translate only.
- URI:
  `arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2021-11-15/functions/${TranslateFunction.Arn}/response-streaming-invocations`.
  The streaming API version is **2021-11-15**, not the buffered 2015-03-31 path.
- Buffered GET/OPTIONS integrations use 2015-03-31 `/invocations`, 29000 ms.
  All route permissions are scoped to this API, HTTP verb and path. The API
  snapshot logical ID changes when the generated route/integration definitions
  change, so a subsequent deployment publishes those changes.

Verified against the AWS documentation (2026-11-21):

- [CloudFormation Method Integration](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-apigateway-method-integration.html)
  defines `ResponseTransferMode`, allowed values `STREAM`/`BUFFERED`, and
  `TimeoutInMillis` with a 50 ms minimum; regional/private timeouts can exceed 29 s.
- [Lambda streaming integration](https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode-lambda.html)
  specifies the 2021-11-15 URI, unchanged REST proxy input, metadata and the
  eight-null-byte delimiter. Sanlang uses `awslambda.HttpResponseStream.from`.
- [Streaming considerations](https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html)
  allow streams up to 15 minutes and state that streaming can exceed 29 seconds
  without an integration-timeout quota increase; regional idle timeout is five
  minutes. This stack caps the integration and Lambda at five minutes. It does
  not request any quota increase. Deployment/region support is not live-verified.

Cloudflare may impose a shorter idle/read timeout, including while a leveled
candidate is hidden for checking. Verify stream flushing, timeout limits and
rules on the approved Cloudflare plan before rollout. Do not cache, transform,
compress or buffer these API responses. The server already sends no-cache
NDJSON headers and never streams an unchecked leveled candidate. Disconnect
cancellation remains best effort; quota is reserved before inference and is not
refunded after an accepted request fails or disconnects.

## Parameters and application contract

All stacks use `Env` and `Prefix` (`DEPLOY_ENV=dev` → `dev-k8s`, prd → `prd-k8s`).
Region is `ap-northeast-1`; the regional ACM certificate must be in that region,
not the us-east-1 certificate used by the shared Cognito custom domain.

| Backend parameter | CLI flag or source | Purpose |
|---|---|---|
| `ImageTag` | `--image-tag` or `SANLANG_IMAGE_TAG`, required | Immutable tag in the repository exported by `sanlang-repository`; URI is composed from that export, not an arbitrary registry |
| `ApiCodeBucket`, `ApiCodeKey` | `--api-code-bucket`, `--api-code-key` or `SANLANG_API_CODE_BUCKET`, `SANLANG_API_CODE_KEY`, required | Existing same-region S3 artifact bucket/key containing `api.mjs` at zip root |
| `ApiCodeVersion` | `--api-code-version`, optional | S3 object version; otherwise use a unique key per release so CloudFormation detects new code |
| `CertificateArn` | `--certificate-arn` or `SANLANG_CERTIFICATE_ARN`, required | ARN from the separate certificate stack's `SanlangCertificateArn` export |
| `WebOrigin` | `SANLANG_WEB_ORIGIN` | Exact HTTPS web origin, no trailing slash; defaults to `https://dev-sanlang.sancode.dev` / `https://sanlang.sancode.dev` (prd). `http://localhost:<port>` is allowed for development |
| `AllowedSources` | `--allowed-sources`, default `cloudflare` | Source-IP gate; `any` is an explicit diagnostic opt-out, not an auth bypass |
| `MaxInputCharacters` | `--max-input-characters`, default 10000 | Input code-point cap |
| `MonthlyCharacterLimit` | `--monthly-character-limit`, default 100000 | Per-user UTC monthly input code points |

The shared Cognito stack parameter `SanlangWebOrigin` receives the same
`SANLANG_WEB_ORIGIN`. Its empty default derives the environment origin above.
It sets callback **and** logout to `<web-origin>/`, matching Sanlang Step 3's
full-page redirect, not `/auth/callback`. Public client, no secret, code flow +
PKCE, `openid email profile`, Cognito/Google identity providers, token revocation,
60-minute ID/access tokens, 30-day refresh token. The server verifies the ID
token; the gateway deliberately has `AuthorizationType: NONE`, so config and
preflight are public while me/translate enforce auth and quota in application code.

Both functions receive `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `AUTH_DOMAIN`
from shared-stack exports; `USAGE_TABLE`, `ALLOWED_SOURCES`, `ALLOWED_ORIGINS`
(the exact `WebOrigin`), `MAX_INPUT_CHARACTERS`, `MONTHLY_CHARACTER_LIMIT`.
Translate additionally receives the production container paths:
`LLAMA_SERVER=/opt/bin/llama-server`, `LLAMA_PORT=8081`,
`MODEL_PATH=/opt/models/model.gguf`, `ADAPTER_PATH=/opt/models/adapter.gguf`.
The model/adapter are baked into the production image with checksums; do not use
the `measurement` target. Never log source text, translations, or ID tokens.

The custom API domain is always **`<env>-sanlang-api.sancode.dev`**, including
`prd-sanlang-api.sancode.dev`. `SanlangApiUrl` has no stage suffix; its root
mapping preserves `/v1/...` in REST proxy events. The default execute-api endpoint
is disabled. Source-IP validation uses `requestContext.identity.sourceIp`, not
spoofable forwarded headers. CORS/OPTIONS is handled by api for all three routes;
do not add a gateway Cognito authorizer to OPTIONS/config. The web build must set
`VITE_SANLANG_API_URL` to `SanlangApiUrl`; without it the switch remains hidden.
Keep web COOP `same-origin` and COEP `require-corp` unchanged.

## Offline checks (safe; no AWS calls)

No Clojure lint/format/test runner is configured in this repository; `make test`
points at an absent root executable. The Nix formatter is for Nix only. Sanlang
adds isolated `clojure.test` invariants and uses the existing template/SAM workflow:

```bash
nix develop --command clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M -m conao3.aws-infra-k8s.sanlang-test
nix develop --command clojure -M -m conao3.aws-infra-k8s template sanlang
# Install current validator tooling (PyPI only, not AWS), then validate offline.
uv tool install cfn-lint==1.61.0
# The four generated files are ignored under target/cfn; never call deploy to generate them.
AWS_EC2_METADATA_DISABLED=true \
  uv tool run --offline --from cfn-lint==1.61.0 cfn-lint --regions ap-northeast-1 \
    --template target/cfn/sanlang{,-repository,-certificate,-cognito}.json
```

`clj-kondo` and `cljfmt` can additionally be run via
`nix shell --inputs-from . nixpkgs#clj-kondo nixpkgs#cljfmt`. They are not project
dependencies. Run `cljfmt check` and `clj-kondo --lint` on the new module/test files.
The pinned SAM CLI 1.172.1 / cfn-lint 1.43.3 predates the `nodejs26.x` schema and
rejects that runtime; the other three templates pass its `sam validate --lint`.
AWS's [supported runtimes](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtimes.html)
lists Node.js 26, matching Sanlang's bundle target. Do not downgrade the runtime
or ignore the error to satisfy the old checker. The Sanlang deploy helper uses
`cfn-lint` and accepts `--cfn-lint /absolute/path/to/cfn-lint` for a current
validator outside the pinned Nix shell; shared tool pins are unchanged.
Template validation is static, not proof of deployment, regional runtime/streaming
support, image architecture, DNS or end-to-end auth.

## Approved rollout only — NOT RUN

**Stop here unless Papa has approved the target environment, costs, artifact
versions and the shared Cognito change set. The commands below call AWS, push
images or deploy resources and were not executed.** Run infrastructure commands
from this repository. Use the matching account/profile and region throughout:

```bash
export DEPLOY_ENV=dev AWS_PROFILE=conao3.dev.k8s AWS_REGION=ap-northeast-1
export PREFIX="$DEPLOY_ENV-k8s"
export SANLANG_WEB_ORIGIN=https://dev-sanlang.sancode.dev
# prd: DEPLOY_ENV=prd, AWS_PROFILE=conao3.prd.k8s,
#      SANLANG_WEB_ORIGIN=https://sanlang.sancode.dev

# Shared Cognito is in ap-northeast-1; only its custom-domain certificate is us-east-1.
nix develop --command clojure -M -m conao3.aws-infra-k8s template sanlang
aws cloudformation create-change-set --region ap-northeast-1 \
  --stack-name "$PREFIX-cognito" --change-set-name sanlang-client \
  --template-body file://target/cfn/sanlang-cognito.json \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameters ParameterKey=Env,UsePreviousValue=true \
    ParameterKey=Prefix,UsePreviousValue=true \
    ParameterKey=DomainName,UsePreviousValue=true \
    ParameterKey=CertificateArn,UsePreviousValue=true \
    "ParameterKey=SanlangWebOrigin,ParameterValue=$SANLANG_WEB_ORIGIN"
aws cloudformation wait change-set-create-complete --region ap-northeast-1 \
  --stack-name "$PREFIX-cognito" --change-set-name sanlang-client
aws cloudformation describe-change-set --region ap-northeast-1 \
  --stack-name "$PREFIX-cognito" --change-set-name sanlang-client
# Stop and review: only the new client/output/parameter should be added.
# Execute only after approval of that exact change set:
aws cloudformation execute-change-set --region ap-northeast-1 \
  --stack-name "$PREFIX-cognito" --change-set-name sanlang-client
aws cloudformation wait stack-update-complete --region ap-northeast-1 \
  --stack-name "$PREFIX-cognito"
# The existing deploy cognito helper also accepts SANLANG_WEB_ORIGIN, but executes
# directly; do not use it as a substitute for this shared-stack change-set review.
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanlang-repository
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanlang-certificate
```

The certificate creation waits for DNS validation. Read its validation CNAME with
`aws acm describe-certificate --certificate-arn <arn>` and add it in Cloudflare
**DNS only**. No DNS automation or Route53 zone is introduced. Copy the regional
certificate ARN to `SANLANG_CERTIFICATE_ARN`.

Build artifacts in the Sanlang checkout after approval (Docker buildx must
support linux/arm64; a single-architecture manifest, not an OCI provenance index):

```bash
# In ~/repos/sanlang
export RELEASE=<unique-git-sha-or-release-tag>
export ECR_URI=<SanlangRepositoryUri-output-from-repository-stack>
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "${ECR_URI%%/*}"
docker buildx build --platform linux/arm64 --target production \
  --provenance=false --sbom=false --push -t "$ECR_URI:$RELEASE" \
  -f apps/server/Dockerfile .
nix develop --command vp run build
# Zip only the buffered API bundle; keep the handler at the archive root.
(cd apps/server/dist/lambda && zip -j api.zip api.mjs)
export SANLANG_API_CODE_BUCKET=<existing-private-artifact-bucket-in-ap-northeast-1>
export SANLANG_API_CODE_KEY="sanlang/$RELEASE/api.zip"
aws s3 cp apps/server/dist/lambda/api.zip \
  "s3://$SANLANG_API_CODE_BUCKET/$SANLANG_API_CODE_KEY"
```

Return to this repository, preserving/exporting the artifact variables:

```bash
export SANLANG_IMAGE_TAG="$RELEASE"
export SANLANG_CERTIFICATE_ARN=<regional-certificate-arn>
# Use the current validator installed in the offline-check section, not Nix's old one.
nix develop --command clojure -M -m conao3.aws-infra-k8s deploy sanlang \
  --cfn-lint "$(uv tool dir)/cfn-lint/bin/cfn-lint"
```

Then create a **proxied** Cloudflare CNAME `<env>-sanlang-api` targeting output
`SanlangApiDomainTarget`, SSL/TLS Full (strict). If Cloudflare Access is enabled,
allow browser CORS preflight/config and bearer-authenticated `/v1/*` API calls
without an interactive Access challenge. Keep Cognito auth enforced in Lambda.
The certificate's validation CNAME stays DNS only. Build/redeploy the web app
with `VITE_SANLANG_API_URL=https://<env>-sanlang-api.sancode.dev`, retaining its
COOP/COEP headers. Check real PKCE/refresh/logout, 401/429, CORS preflight, usage,
leveled check/fallback and NDJSON flushing before enabling Server for users.

## Operations and rollback

Watch Lambda Errors, Throttles, duration/memory, REST API 5xx/latency and DynamoDB
throttles. Streaming, 10 GB Lambda duration, ECR storage/scanning, PITR and logs
cost money. No billing-based entitlement is provisioned. No source/translated
text should appear in logs or gateway tracing. Account-wide concurrency and
Gateway throttles should be reviewed before rollout; they are not changed here.

Rollback by deploying a retained earlier immutable image tag and matching earlier
zip key/version, or rebuilding the web with no API URL to hide Server. Keep
browser inference available. Data restoration uses DynamoDB PITR to a new table
and an explicitly reviewed migration. Removal order: backend → certificate and
repository → shared Cognito client/exports once unused; remove Cloudflare records
manually. Table/repository/log-group retention intentionally leaves data/images/logs behind;
deletion protection must be explicitly disabled before deleting the retained
table. Remove old code artifacts under the artifact bucket's own retention policy.
