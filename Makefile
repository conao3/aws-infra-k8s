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

.PHONY: build-image
build-image:
	bin/image build

.PHONY: upload-image
upload-image:
	bin/image upload

.PHONY: deploy-cluster-custom
deploy-cluster-custom:
	bin/image deploy

.PHONY: run-vm
run-vm:
	@echo "Starting NixOS VM (x86_64)..."
	@echo "To exit: Press Ctrl-A then X"
	@echo ""
	QEMU_OPTS="${QEMU_OPTS:--m 4096 -smp 4}" nix run .#vm
