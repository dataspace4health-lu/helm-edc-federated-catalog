timestamp = $(shell date "+%Y.%m.%d-%H.%M.%S")

#function to update helm dependencies and package the chart and save the tgz in helm directory
define build =
helm dependency update
helm package . -d helm
endef

define test =
kubectl run playwright --image mcr.microsoft.com/playwright:v1.46.1 --command -- bash -c "mkdir -p app/tests && sleep infinity"
kubectl wait --for=condition=ready pod playwright
kubectl cp tests playwright:/app
-kubectl exec playwright -- bash -c "cd /app/tests && export CI=1 && npm i --silent && npx -y playwright test --output ./results"
if [ $$? -eq 0 ]; then \
	kubectl cp playwright:/app/tests/results/ tests/results/${timestamp}/traces; \
	kubectl cp playwright:/app/tests/playwright-report/ tests/results/${timestamp}/report; \
fi

kubectl delete pod playwright
endef

SUBDIRS := $(wildcard src/*/.)
CURRENT_DIR := $(notdir $(shell pwd))

.PHONY: all build $(SUBDIRS) install test uninstall clean

all: build install test uninstall clean

refresh: uninstall clean build install

build: $(SUBDIRS) helm/*.tgz
helm/*.tgz:
	$(build)

$(SUBDIRS):
	$(MAKE) -C $@ $(MAKECMDGOALS)

install:
#Ensure the namespace exists (ignore error if it already exists)
	kubectl create namespace edc 2> /dev/null || true
#Install or upgrade Helm release into the namespace
	helm upgrade --install $(CURRENT_DIR) helm/*.tgz --namespace edc

test:
	$(test)

uninstall:
	kubectl delete pod playwright 2> /dev/null || true
	helm uninstall $(CURRENT_DIR) --namespace edc 2> /dev/null || true

clean: $(SUBDIRS)
	rm -rf charts helm