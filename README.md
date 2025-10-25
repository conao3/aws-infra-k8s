# aws-infra-k8s

# Deploy

Deploy all via this command.

```
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all
```

Deploy each modules like this.
Currently below modules are provided.

- `network`
- `routing`
- `security-group`
- `ssh-tunnel`
- `eice`

```
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy routing
```
