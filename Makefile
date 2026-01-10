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

.PHONY: build-ec2-image
build-ec2-image:
	nix build .#ec2-image

.PHONY: upload-ec2-image
upload-ec2-image: build-ec2-image
	aws s3 cp result/nixos.vhd s3://aws-sam-cli-managed-default-samclisourcebucket-9ipbfd2ab3os/nixos-custom.vhd
	aws ec2 import-snapshot --description "NixOS Custom EC2 Image" --disk-container "Format=VHD,UserBucket={S3Bucket=aws-sam-cli-managed-default-samclisourcebucket-9ipbfd2ab3os,S3Key=nixos-custom.vhd}"
