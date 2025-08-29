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


uninstall:
	kubectl delete pod playwright 2> /dev/null || true
	helm uninstall $(CURRENT_DIR) --namespace edc 2> /dev/null || true

clean: $(SUBDIRS)
	rm -rf charts helm