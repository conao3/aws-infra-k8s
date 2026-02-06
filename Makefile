.PHONY: clean
clean:
	rm -rf target

.PHONY: uberjar
uberjar:
	clojure -T:build uberjar

.PHONY: native
native: uberjar
	native-image -jar target/aws-infra-k8s-0.1.0-standalone.jar \
		-H:+ReportExceptionStackTraces \
		--no-fallback \
		--initialize-at-build-time \
		-H:Name=target/aws-infra-k8s

.PHONY: run
run:
	clojure -M -m conao3.aws-infra-k8s

.PHONY: test
test:
	./aws-infra-k8s

.PHONY: update-cloudflare-ips
update-cloudflare-ips:
	./bin/update-cloudflare-ips
