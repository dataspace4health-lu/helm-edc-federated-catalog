timestamp = $(shell date "+%Y.%m.%d-%H.%M.%S")

#function to update helm dependencies and package the chart and save the tgz in helm directory
define build =
helm dependency update
helm package . -d helm
endef

SUBDIRS := $(wildcard components/*/.)
CURRENT_DIR := $(notdir $(shell pwd))

.PHONY: all build $(SUBDIRS) install test uninstall clean

all: build install test uninstall clean

refresh: uninstall clean build

build: $(SUBDIRS) helm/*.tgz
helm/*.tgz:
	$(build)

$(SUBDIRS):
	$(MAKE) -C $@ $(MAKECMDGOALS)

install:
ifeq ($(strip $(NAMESPACE)),)
	$(eval NAMESPACE := default)
	@echo "NAMESPACE is not set, using default. Usage: make install NAMESPACE=$(NAMESPACE)"
endif

	@echo "Installing on namespace $(NAMESPACE)"
#Ensure the namespace exists (ignore error if it already exists)
	kubectl create namespace $(NAMESPACE) 2> /dev/null || true
#Install or upgrade Helm release into the namespace
	helm install $(CURRENT_DIR) helm/*.tgz --namespace $(NAMESPACE)

uninstall:
ifeq ($(strip $(NAMESPACE)),)
	$(eval NAMESPACE := default)
	@echo "NAMESPACE is not set, using default. Usage: make uninstall NAMESPACE=$(NAMESPACE)"
endif
# 	kubectl delete pod playwright 2> /dev/null || true
	
	@echo "Uninstalling on namespace $(NAMESPACE)"
	helm uninstall $(CURRENT_DIR) --namespace $(NAMESPACE) 2> /dev/null || true
	kubectl delete pvc data-$(CURRENT_DIR)-postgresql-0 --namespace $(NAMESPACE) 2> /dev/null || true
	kubectl delete namespace $(NAMESPACE) --wait=true --timeout=60s 2> /dev/null || true
	
clean: $(SUBDIRS)
	rm -rf charts helm