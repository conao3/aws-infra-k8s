all:


.PHONY: all
all:
	@cat $(MAKEFILE_LIST) | grep -v '^\t' | grep -E '^[^:]+:.*?## .*$$' | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'


.PHONY: init
init:  ## initialize project
	$(MAKE) gen


.PHONY: gen
gen:  ## generate files
	$(MAKE) gen -C deploy
